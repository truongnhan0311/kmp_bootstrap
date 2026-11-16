package kmp.bootstrap.appkit.mqtt

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Broker MQTT 3.1.1 tối thiểu chạy trong test, qua socket TCP thật, để thử những thứ broker thật khó tạo ra theo ý:
 * từ chối mật khẩu, nuốt PUBACK, im lặng không trả PINGRESP, cắt mọi kết nối. Có Last Will và định tuyến theo bộ lọc.
 */
class FakeBroker(var auth: (user: String?, pass: String?) -> Int = { _, _ -> 0 }) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val clients = CopyOnWriteArrayList<Conn>()
    /** Mọi gói nhận được từ mọi client, theo thứ tự. */
    val received = CopyOnWriteArrayList<MqttPacket>()
    @Volatile var dropPubAcks = 0          // số PUBACK sắp tới sẽ bị nuốt
    @Volatile var answerPings = true
    @Volatile var acceptConnections = true
    @Volatile var answerConnect = true     // false: nhận CONNECT nhưng không bao giờ trả CONNACK

    init {
        thread(isDaemon = true, name = "fake-broker") {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (!acceptConnections) { s.close(); continue }
                val c = Conn(s); clients += c
                thread(isDaemon = true) { c.serve() }
            }
        }
    }

    fun connects(): List<MqttPacket.Connect> = received.filterIsInstance<MqttPacket.Connect>()
    fun publishes(): List<MqttPacket.Publish> = received.filterIsInstance<MqttPacket.Publish>()

    /** Cắt mọi kết nối như mạng rớt (không DISCONNECT → Last Will được phát). */
    fun kickAll() { clients.forEach { it.kill() } }

    /** Gửi tin từ "phía server" tới mọi client đã đăng ký khớp. */
    fun publish(topic: String, payload: String, qos: Int = 1) = route(MqttPacket.Publish(topic, payload.encodeToByteArray(), qos, packetId = if (qos == 1) 1 else null))

    private fun route(p: MqttPacket.Publish) {
        for (c in clients) {
            val q = c.subs.filter { MqttTopic.matches(it.key, p.topic) }.maxOfOrNull { minOf(it.value, p.qos) } ?: continue
            c.send(MqttPacket.Publish(p.topic, p.payload, q, p.retain, false, if (q == 1) c.nextId() else null))
        }
    }

    override fun close() { server.close(); kickAll() }

    inner class Conn(private val socket: Socket) {
        val subs: MutableMap<String, Int> = java.util.concurrent.ConcurrentHashMap<String, Int>()   // kiểu Map: `in` kiểm KHOÁ
        private var will: MqttWill? = null
        private var clean = false
        private var id = 0
        @Volatile var clientId: String? = null
        fun nextId() = synchronized(this) { id = id % 65535 + 1; id }

        fun send(p: MqttPacket) = synchronized(socket) {
            runCatching { socket.getOutputStream().write(MqttCodec.encode(p)); socket.getOutputStream().flush() }
        }

        fun kill() { runCatching { socket.close() } }

        fun serve() {
            val dec = MqttCodec.Decoder(); val buf = ByteArray(4096); val input = socket.getInputStream()
            try {
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    dec.feed(buf.copyOf(n))
                    while (true) { val p = dec.next() ?: break; received += p; if (!handle(p)) return }
                }
            } catch (e: IOException) {
                // rớt
            } finally {
                clients.remove(this)
                if (!clean) will?.let { route(MqttPacket.Publish(it.topic, it.payload, it.qos, it.retain, false, if (it.qos == 1) 1 else null)) }
                kill()
            }
        }

        private fun handle(p: MqttPacket): Boolean {
            when (p) {
                is MqttPacket.Connect -> {
                    if (!answerConnect) return true
                    val code = auth(p.username, p.password)
                    send(MqttPacket.ConnAck(false, code))
                    if (code != 0) return false
                    clientId = p.clientId; will = p.will
                }
                is MqttPacket.Publish -> {
                    if (p.qos == 1) { if (dropPubAcks > 0) dropPubAcks-- else send(MqttPacket.PubAck(p.packetId!!)) }
                    route(p)
                }
                is MqttPacket.Subscribe -> { p.filters.forEach { (f, q) -> subs[f] = q }; send(MqttPacket.SubAck(p.packetId, p.filters.map { it.second })) }
                is MqttPacket.Unsubscribe -> { p.filters.forEach { subs.remove(it) }; send(MqttPacket.UnsubAck(p.packetId)) }
                MqttPacket.PingReq -> if (answerPings) send(MqttPacket.PingResp)
                MqttPacket.Disconnect -> { clean = true; return false }
                else -> Unit
            }
            return true
        }
    }
}

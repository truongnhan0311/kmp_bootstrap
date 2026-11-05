package kmp.bootstrap.appkit.mqtt

import kmp.bootstrap.appkit.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/*
 * Client MQTT 3.1.1 dùng chung cho app:
 *   val mqtt = MqttClient(MqttOptions(clientId = "dev-1", will = …, credentials = { MqttCredentials("dev-1", sign()) }),
 *                         WebSocketConnector(mqttHttpClient()) { "wss://mq.example.com/mqtt" }, scope)
 *   mqtt.start(); mqtt.subscribe("in/dev-1"); mqtt.messages.collect { … }; mqtt.publish("out/dev-1", bytes)
 *
 * Tự lo: CONNECT + Last Will, PUBACK cho tin QoS 1 nhận được, PINGREQ theo keepAlive (không có PINGRESP thì coi như rớt),
 * nối lại với thời gian chờ ngẫu nhiên đều trong [0, min(maxDelay, 2^n)] giây (về n = 0 sau khi ổn định stableReset giây),
 * đăng ký lại mọi chủ đề sau khi nối lại, hỏi lại [MqttOptions.credentials] ở MỖI lần nối (mật khẩu có hạn/có ký thời gian).
 * Không tự gửi lại tin publish khi rớt mạng: publish đang chờ PUBACK nhận [MqttException.Reason.ConnectionLost], app tự quyết.
 */

data class MqttCredentials(val username: String? = null, val password: String? = null)

data class ReconnectPolicy(
    val maxDelaySeconds: Int = 60,
    val stableResetSeconds: Int = 60,
    val random: Random = Random.Default,
) {
    /** Thời gian chờ trước lần nối lại thứ [attempt] (0 = lần rớt đầu): ngẫu nhiên đều trong [0, min(max, 2^attempt)] giây. */
    fun delayMs(attempt: Int): Long {
        val capSeconds = if (attempt >= 30) maxDelaySeconds.toLong() else minOf(maxDelaySeconds.toLong(), 1L shl attempt)
        return random.nextLong(0, capSeconds * 1000 + 1)
    }
}

data class MqttOptions(
    val clientId: String,
    val keepAliveSeconds: Int = 60,
    val cleanSession: Boolean = true,
    val will: MqttWill? = null,
    val credentials: suspend () -> MqttCredentials = { MqttCredentials() },
    val connectTimeoutMs: Long = 10_000,
    val ackTimeoutMs: Long = 10_000,
    val reconnect: ReconnectPolicy = ReconnectPolicy(),
)

sealed interface MqttState {
    data object Idle : MqttState
    data class Connecting(val attempt: Int) : MqttState
    data object Connected : MqttState
    /** Đang chờ [delayMs] trước lần nối thứ [attempt]; [lastError] là lý do lần trước hỏng (tiếng Anh, không chứa mật khẩu). */
    data class Waiting(val attempt: Int, val delayMs: Long, val lastError: String?) : MqttState
    data object Stopped : MqttState
}

class MqttMessage(val topic: String, val payload: ByteArray, val qos: MqttQos, val retained: Boolean) {
    override fun toString() = "MqttMessage(topic=$topic, bytes=${payload.size}, qos=$qos, retained=$retained)"
}

class MqttException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NotConnected, Timeout, ConnectionLost, Refused, SubscribeRejected }
}

class MqttClient(
    private val options: MqttOptions,
    private val connector: MqttConnector,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<MqttState>(MqttState.Idle)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 256)
    /** Tin nhận được. Chủ đề retained cũng đến đây ngay sau khi đăng ký. */
    val messages: SharedFlow<MqttMessage> = _messages.asSharedFlow()

    private val lock = Mutex()
    private val sendLock = Mutex()
    private val subscriptions = linkedMapOf<String, MqttQos>()
    private val pending = mutableMapOf<Int, CompletableDeferred<MqttPacket>>()
    private var nextId = 1
    private var connection: MqttConnection? = null
    private var loop: Job? = null
    @kotlin.concurrent.Volatile private var stopped = false
    @kotlin.concurrent.Volatile private var awaitingPong = false

    fun start() {
        check(loop == null) { "MqttClient already started" }
        stopped = false
        loop = scope.launch { runLoop() }
    }

    /** Ngắt êm: gửi DISCONNECT (broker KHÔNG phát Last Will), dừng nối lại. */
    suspend fun stop() {
        stopped = true
        val conn = lock.withLock { connection }
        if (conn != null) {
            withTimeoutOrNull(2_000) { sendLock.withLock { conn.send(MqttCodec.encode(MqttPacket.Disconnect)) } }
            runCatching { conn.close() }
        }
        loop?.cancelAndJoin()
        loop = null
        _state.value = MqttState.Stopped
        AppLog.i(TAG, "stopped client=${options.clientId}")
    }

    /** Đăng ký chủ đề; được nhớ và tự đăng ký lại sau mỗi lần nối lại. Chưa nối thì chỉ ghi nhớ. */
    suspend fun subscribe(topic: String, qos: MqttQos = MqttQos.AtLeastOnce) {
        lock.withLock { subscriptions[topic] = qos }
        if (_state.value == MqttState.Connected) sendSubscribe(listOf(topic to qos))
    }

    suspend fun unsubscribe(topic: String) {
        lock.withLock { subscriptions.remove(topic) }
        if (_state.value != MqttState.Connected) return
        val id = newId()
        request(id, MqttPacket.Unsubscribe(id, listOf(topic)))
    }

    /** Gửi tin. QoS 1 chờ PUBACK (tối đa [MqttOptions.ackTimeoutMs]). Chưa nối thì ném [MqttException.Reason.NotConnected]. */
    suspend fun publish(topic: String, payload: ByteArray, qos: MqttQos = MqttQos.AtLeastOnce, retain: Boolean = false) {
        if (_state.value != MqttState.Connected) throw MqttException(MqttException.Reason.NotConnected, "not connected")
        if (qos == MqttQos.AtMostOnce) {
            send(MqttPacket.Publish(topic, payload, qos, retain))
            return
        }
        val id = newId()
        request(id, MqttPacket.Publish(topic, payload, qos, retain, packetId = id))
    }

    suspend fun publish(topic: String, text: String, qos: MqttQos = MqttQos.AtLeastOnce, retain: Boolean = false) =
        publish(topic, text.encodeToByteArray(), qos, retain)

    // ------------------------------------------------------------------ bên trong

    private suspend fun runLoop() {
        var attempt = 0
        var lastError: String? = null
        while (scope.isActive && !stopped) {
            _state.value = MqttState.Connecting(attempt)
            try {
                connectOnce(onStable = { attempt = 0 })
                lastError = "connection closed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e.message ?: e::class.simpleName
            } finally {
                withContext(NonCancellable) { dropConnection() }
            }
            if (stopped) break
            val wait = options.reconnect.delayMs(attempt)
            AppLog.w(TAG, "disconnected client=${options.clientId} attempt=$attempt retryInMs=$wait reason=$lastError")
            _state.value = MqttState.Waiting(attempt, wait, lastError)
            delay(wait)
            attempt++
        }
    }

    private suspend fun connectOnce(onStable: () -> Unit) {
        val conn = withTimeout(options.connectTimeoutMs) { connector.open() }
        lock.withLock { connection = conn }
        val creds = options.credentials()
        val reader = MqttFrameReader()
        conn.send(MqttCodec.encode(MqttPacket.Connect(options.clientId, creds.username, creds.password,
            options.keepAliveSeconds, options.cleanSession, options.will)))

        val early = mutableListOf<MqttPacket>()
        val ack = withTimeout(options.connectTimeoutMs) {
            var found: MqttPacket.ConnAck? = null
            while (found == null) {
                val chunk = conn.receive() ?: throw MqttException(MqttException.Reason.ConnectionLost, "closed before CONNACK")
                for (p in reader.feed(chunk)) if (found == null && p is MqttPacket.ConnAck) found = p else early += p
            }
            found
        }
        if (ack.returnCode != 0) {
            val why = MqttRefusal.of(ack.returnCode)?.name ?: "code ${ack.returnCode}"
            throw MqttException(MqttException.Reason.Refused, "connection refused: $why")
        }
        awaitingPong = false
        _state.value = MqttState.Connected
        AppLog.i(TAG, "connected client=${options.clientId} sessionPresent=${ack.sessionPresent}")

        coroutineScope {
            val readerJob = launch {
                early.forEach { dispatch(it) }
                while (true) {
                    val chunk = conn.receive() ?: throw MqttException(MqttException.Reason.ConnectionLost, "connection closed by peer")
                    reader.feed(chunk).forEach { dispatch(it) }
                }
            }
            if (options.keepAliveSeconds > 0) launch {
                while (true) {
                    delay(options.keepAliveSeconds * 1000L)
                    if (awaitingPong) throw MqttException(MqttException.Reason.Timeout, "no PINGRESP within keepAlive")
                    awaitingPong = true
                    send(MqttPacket.PingReq)
                }
            }
            launch { delay(options.reconnect.stableResetSeconds * 1000L); onStable() }
            val subs = lock.withLock { subscriptions.toList() }
            if (subs.isNotEmpty()) {
                // Đăng ký lại sau khi nối: broker từ chối một chủ đề thì chỉ ghi log, không rớt kết nối (tránh vòng nối lại vô hạn).
                try { sendSubscribe(subs) } catch (e: MqttException) {
                    if (e.reason != MqttException.Reason.SubscribeRejected) throw e
                    AppLog.w(TAG, e.message ?: "subscribe rejected")
                }
            }
            readerJob.join()
        }
    }

    private suspend fun dispatch(p: MqttPacket) {
        when (p) {
            is MqttPacket.Publish -> {
                if (p.qos == MqttQos.AtLeastOnce) send(MqttPacket.PubAck(p.packetId!!))
                _messages.emit(MqttMessage(p.topic, p.payload, p.qos, p.retain))
            }
            is MqttPacket.PubAck -> complete(p.packetId, p)
            is MqttPacket.SubAck -> complete(p.packetId, p)
            is MqttPacket.UnsubAck -> complete(p.packetId, p)
            MqttPacket.PingResp -> awaitingPong = false
            else -> AppLog.w(TAG, "ignored unexpected packet ${p::class.simpleName}")
        }
    }

    private suspend fun sendSubscribe(topics: List<Pair<String, MqttQos>>) {
        val id = newId()
        val ack = request(id, MqttPacket.Subscribe(id, topics)) as MqttPacket.SubAck
        val rejected = topics.filterIndexed { i, _ -> (ack.returnCodes.getOrNull(i) ?: 0x80) == 0x80 }.map { it.first }
        if (rejected.isNotEmpty()) throw MqttException(MqttException.Reason.SubscribeRejected, "subscribe rejected: $rejected")
    }

    private suspend fun request(id: Int, packet: MqttPacket): MqttPacket {
        val waiter = CompletableDeferred<MqttPacket>()
        lock.withLock { pending[id] = waiter }
        try {
            send(packet)
            return withTimeoutOrNull(options.ackTimeoutMs) { waiter.await() }
                ?: throw MqttException(MqttException.Reason.Timeout, "no ack for packet $id")
        } finally {
            lock.withLock { pending.remove(id) }
        }
    }

    private suspend fun complete(id: Int, p: MqttPacket) { lock.withLock { pending[id] }?.complete(p) }

    private suspend fun send(p: MqttPacket) {
        val conn = lock.withLock { connection } ?: throw MqttException(MqttException.Reason.NotConnected, "not connected")
        sendLock.withLock { conn.send(MqttCodec.encode(p)) }
    }

    private suspend fun newId(): Int = lock.withLock {
        var id = nextId
        while (id in pending) id = if (id == 65535) 1 else id + 1
        nextId = if (id == 65535) 1 else id + 1
        id
    }

    private suspend fun dropConnection() {
        val (conn, waiters) = lock.withLock {
            val c = connection; connection = null
            val w = pending.values.toList(); pending.clear()
            c to w
        }
        waiters.forEach { it.completeExceptionally(MqttException(MqttException.Reason.ConnectionLost, "connection lost")) }
        if (conn != null) runCatching { conn.close() }
    }

    private companion object { const val TAG = "mqtt" }
}

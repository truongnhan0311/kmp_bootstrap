package kmp.bootstrap.appkit.mqtt

import kmp.bootstrap.appkit.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/*
 * Client MQTT 3.1.1 tự giữ kết nối:
 *   - start() rồi để đó: rớt mạng, broker khởi động lại, mật khẩu hết hạn… đều tự nối lại, giãn cách 1s, 2s, 4s… tối đa 30s (±20%).
 *   - Mật khẩu là HÀM, gọi lại MỖI lần nối (mật khẩu thiết bị kiểu Tuya có hạn 5 phút; vé phiên chỉ dùng một lần).
 *   - subscribe(filter) trả Flow: đang có người nghe thì broker đăng ký, nối lại thì đăng ký lại, ngừng nghe thì huỷ đăng ký.
 *   - publish QoS 1 treo tới khi broker xác nhận; rớt mạng giữa chừng thì gửi lại (cờ DUP) sau khi nối lại → "ít nhất một lần".
 *     Bên nhận phải chống trùng (appkit-iot DuplicateFilter).
 *   - stop() gửi DISCONNECT nên broker KHÔNG phát Last Will; rớt mạng thật thì broker phát.
 * Không có QoS 2, không giữ phiên giữa các lần nối (cleanSession = true là mặc định và là cách dùng được kiểm).
 */
data class MqttOptions(
    val clientId: String,
    val username: String? = null,
    /** Gọi lại mỗi lần nối. Không bao giờ ghi log. */
    val password: (suspend () -> String?)? = null,
    val keepAlive: Duration = 30.seconds,
    val cleanSession: Boolean = true,
    val will: MqttWill? = null,
    val connectTimeout: Duration = 10.seconds,
    val backoffMin: Duration = 1.seconds,
    val backoffMax: Duration = 30.seconds,
) {
    init {
        require(clientId.isNotEmpty() && clientId.encodeToByteArray().size <= 65535) { "clientId must not be empty" }
        require(keepAlive >= 1.seconds && keepAlive.inWholeSeconds <= 65535) { "keepAlive must be 1..65535 seconds" }
        require(backoffMin > Duration.ZERO && backoffMax >= backoffMin) { "need 0 < backoffMin <= backoffMax" }
    }
}

class MqttMessage(val topic: String, val payload: ByteArray, val qos: Int, val retain: Boolean, val dup: Boolean) {
    val text: String get() = payload.decodeToString()
    override fun toString() = "MqttMessage($topic, ${payload.size} bytes, qos=$qos, retain=$retain)"
}

/** Vì sao kết nối hỏng. [code] là mã CONNACK khi broker từ chối (4 = sai tên/mật khẩu, 5 = không được phép). */
class MqttError(val kind: Kind, message: String, val code: Int? = null) : Exception(message) {
    enum class Kind { Network, Refused, Protocol, Timeout }
    val badCredentials: Boolean get() = kind == Kind.Refused && (code == 4 || code == 5)
}

/** Gọi publish/stop khi client đã dừng, hoặc publish QoS 0 khi đang mất kết nối. */
class MqttNotConnectedException(message: String) : Exception(message)

sealed interface MqttState {
    data object Idle : MqttState
    data class Connecting(val attempt: Int) : MqttState
    data object Connected : MqttState
    /** Đang chờ nối lại sau [lastError]; [attempt] lần thất bại liên tiếp. */
    data class Waiting(val attempt: Int, val retryIn: Duration, val lastError: MqttError) : MqttState
    data object Stopped : MqttState
}

class MqttClient(
    private val options: MqttOptions,
    private val connector: MqttConnector,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow<MqttState>(MqttState.Idle)
    val state: StateFlow<MqttState> = _state.asStateFlow()

    private val incoming = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 256)
    private val lock = Mutex()                      // giữ các bảng dưới đây
    private val writeLock = Mutex()                 // một gói ghi xong mới ghi gói khác
    private var transport: MqttTransport? = null   // đã nối xong (sau CONNACK): dùng để gửi
    private var opening: MqttTransport? = null     // đang mở/chờ CONNACK: chỉ để stop() đóng được
    private val subscriptions = LinkedHashMap<String, Sub>()
    private val pending = LinkedHashMap<Int, Pending>()
    private var nextId = 0
    private var loop: Job? = null
    private var stopped = false

    private class Sub(val qos: Int, var listeners: Int)
    private class Pending(val packet: MqttPacket.Publish, val done: CompletableDeferred<Unit>)

    /** Bắt đầu nối (và tự nối lại). Gọi nhiều lần không sao. */
    fun start() {
        if (loop != null) return
        loop = scope.launch { run() }
    }

    /** Ngắt có chủ đích: gửi DISCONNECT (broker không phát Last Will), huỷ mọi publish đang chờ. Client không dùng lại được. */
    suspend fun stop() {
        stopped = true
        val (t, half) = lock.withLock { transport to opening }
        if (t != null) {
            runCatching { writeLock.withLock { t.send(MqttCodec.encode(MqttPacket.Disconnect)) } }
            t.close()   // lệnh đọc đang chặn (TCP) chỉ thoát khi kết nối đóng; huỷ coroutine thôi là không đủ
        }
        half?.close()   // đang chờ CONNACK: đóng luôn, khỏi chờ hết connectTimeout
        loop?.cancel(); loop?.join()
        lock.withLock {
            pending.values.forEach { it.done.completeExceptionally(MqttNotConnectedException("client stopped")) }
            pending.clear()
        }
        _state.value = MqttState.Stopped
    }

    /** Chờ tới khi nối xong (hoặc hết [timeout] → TimeoutCancellationException). */
    suspend fun awaitConnected(timeout: Duration = 30.seconds) {
        withTimeout(timeout) { state.first { it == MqttState.Connected || it == MqttState.Stopped } }
        if (state.value == MqttState.Stopped) throw MqttNotConnectedException("client stopped")
    }

    /**
     * QoS 0: gửi ngay nếu đang nối, không thì ném [MqttNotConnectedException].
     * QoS 1: treo tới khi broker xác nhận, kể cả qua các lần nối lại; huỷ coroutine gọi thì thôi chờ (tin có thể đã đi).
     */
    suspend fun publish(topic: String, payload: ByteArray, qos: Int = 1, retain: Boolean = false) {
        require(qos in 0..1) { "qos must be 0 or 1" }
        require(topic.isNotEmpty() && '+' !in topic && '#' !in topic) { "topic must not be empty or contain wildcards" }
        if (stopped) throw MqttNotConnectedException("client stopped")
        if (qos == 0) {
            val t = lock.withLock { transport } ?: throw MqttNotConnectedException("not connected")
            writeLock.withLock { t.send(MqttCodec.encode(MqttPacket.Publish(topic, payload, 0, retain))) }
            return
        }
        val (entry, t) = lock.withLock {
            val id = newId()
            Pending(MqttPacket.Publish(topic, payload, 1, retain, false, id), CompletableDeferred()).also { pending[id] = it } to transport
        }
        if (t != null) runCatching { writeLock.withLock { t.send(MqttCodec.encode(entry.packet)) } }   // lỗi: sẽ gửi lại khi nối lại
        try {
            entry.done.await()
        } finally {
            lock.withLock { pending.remove(entry.packet.packetId) }
        }
    }

    suspend fun publish(topic: String, text: String, qos: Int = 1, retain: Boolean = false) =
        publish(topic, text.encodeToByteArray(), qos, retain)

    /**
     * Tin đến khớp [filter]. Broker được đăng ký khi Flow bắt đầu được thu và huỷ khi người thu cuối cùng dừng.
     * Nhiều nơi nghe cùng filter chỉ tạo một đăng ký.
     */
    fun subscribe(filter: String, qos: Int = 1): Flow<MqttMessage> {
        require(MqttTopic.isValidFilter(filter)) { "invalid topic filter: $filter" }
        require(qos in 0..1) { "qos must be 0 or 1" }
        return incoming
            .onSubscription { addListener(filter, qos) }
            .onCompletion { withContext(NonCancellable) { removeListener(filter) } }
            .filter { MqttTopic.matches(filter, it.topic) }
    }

    // ---------------------------------------------------------------- nội bộ

    private fun newId(): Int { nextId = nextId % 65535 + 1; while (pending.containsKey(nextId)) nextId = nextId % 65535 + 1; return nextId }

    private suspend fun addListener(filter: String, qos: Int) {
        val t = lock.withLock {
            val s = subscriptions[filter]
            if (s != null) { s.listeners++; return }
            subscriptions[filter] = Sub(qos, 1)
            transport?.let { it to newId() }
        } ?: return
        runCatching { writeLock.withLock { t.first.send(MqttCodec.encode(MqttPacket.Subscribe(t.second, listOf(filter to qos)))) } }
    }

    private suspend fun removeListener(filter: String) {
        val t = lock.withLock {
            val s = subscriptions[filter] ?: return
            if (--s.listeners > 0) return
            subscriptions.remove(filter)
            transport?.let { it to newId() }
        } ?: return
        runCatching { writeLock.withLock { t.first.send(MqttCodec.encode(MqttPacket.Unsubscribe(t.second, listOf(filter)))) } }
    }

    private suspend fun run() {
        var failures = 0
        while (!stopped) {
            _state.value = MqttState.Connecting(failures)
            val error: MqttError = try {
                session { failures = 0 }
                MqttError(MqttError.Kind.Network, "connection closed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: MqttError) {
                e
            } catch (e: MqttProtocolException) {
                MqttError(MqttError.Kind.Protocol, e.message ?: "protocol error")
            } catch (e: Throwable) {
                MqttError(MqttError.Kind.Network, e.message ?: e::class.simpleName ?: "network error")
            }
            if (stopped) break
            failures++
            val wait = backoff(failures)
            AppLog.w("mqtt", "disconnected (${error.kind}${error.code?.let { " code=$it" } ?: ""}): ${error.message}; retry in $wait")
            _state.value = MqttState.Waiting(failures, wait, error)
            delay(wait)
        }
    }

    /** Một lần nối: mở, CONNECT, đăng ký lại, gửi lại QoS 1 dở, rồi đọc tới khi hỏng. */
    private suspend fun session(onConnected: () -> Unit) {
        val t = try {
            withTimeout(options.connectTimeout) { connector.connect() }
        } catch (e: CancellationException) {
            if (stopped) throw e
            throw MqttError(MqttError.Kind.Timeout, "connect timed out")
        }
        val decoder = MqttCodec.Decoder()
        try {
            lock.withLock { opening = t }
            if (stopped) throw CancellationException("client stopped")
            val connect = MqttPacket.Connect(
                options.clientId, options.username, options.password?.invoke(),
                options.keepAlive.inWholeSeconds.toInt(), options.cleanSession, options.will,
            )
            t.send(MqttCodec.encode(connect))
            val ack = closingAfter(t, options.connectTimeout, "no CONNACK") { readPacket(t, decoder) }
            if (ack !is MqttPacket.ConnAck) throw MqttError(MqttError.Kind.Protocol, "expected CONNACK, got $ack")
            if (ack.returnCode != 0) throw MqttError(MqttError.Kind.Refused, "connection refused", ack.returnCode)

            val (subs, resend) = lock.withLock {
                transport = t
                subscriptions.map { (f, s) -> f to s.qos } to pending.values.map { it.packet.copy(dup = true) }
            }
            writeLock.withLock {
                if (subs.isNotEmpty()) t.send(MqttCodec.encode(MqttPacket.Subscribe(lock.withLock { newId() }, subs)))
                resend.forEach { t.send(MqttCodec.encode(it)) }
            }
            onConnected()
            _state.value = MqttState.Connected
            AppLog.i("mqtt", "connected as ${options.clientId}")

            val clock = TimeSource.Monotonic
            var lastIn = clock.markNow()
            var lastOut = clock.markNow()
            var silent = false   // pinger đóng kết nối vì broker im lặng → báo Timeout, không phải lỗi mạng của lệnh đọc
            try { coroutineScope {
                val pinger = launch {
                    val step = (options.keepAlive / 4).coerceAtLeast(100.milliseconds)
                    while (true) {
                        delay(step)
                        if (lastIn.elapsedNow() > options.keepAlive * 1.5) {
                            silent = true
                            t.close()   // để lệnh đọc đang chặn thoát ra
                            throw MqttError(MqttError.Kind.Timeout, "broker silent for ${options.keepAlive * 1.5}")
                        }
                        if (lastOut.elapsedNow() >= options.keepAlive) {
                            writeLock.withLock { t.send(MqttCodec.encode(MqttPacket.PingReq)) }
                            lastOut = clock.markNow()
                        }
                    }
                }
                try {
                    while (true) {
                        val p = readPacket(t, decoder)
                        lastIn = clock.markNow()
                        handle(t, p)
                    }
                } finally {
                    pinger.cancel()
                }
            } } catch (e: Throwable) {
                if (silent && e !is CancellationException) throw MqttError(MqttError.Kind.Timeout, "broker silent for ${options.keepAlive * 1.5}")
                throw e
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock { if (transport === t) transport = null; if (opening === t) opening = null }
                t.close()
            }
        }
    }

    /** Như withTimeout nhưng ĐÓNG kết nối khi hết giờ: lệnh đọc chặn luồng (socket TCP) không huỷ được bằng cách khác. */
    private suspend fun <T> closingAfter(t: MqttTransport, limit: Duration, what: String, block: suspend () -> T): T = coroutineScope {
        var fired = false
        val watchdog = launch { delay(limit); fired = true; t.close() }
        try {
            block()
        } catch (e: Throwable) {
            if (fired && e !is CancellationException) throw MqttError(MqttError.Kind.Timeout, what)
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun readPacket(t: MqttTransport, decoder: MqttCodec.Decoder): MqttPacket {
        while (true) {
            decoder.next()?.let { return it }
            decoder.feed(t.receive())
        }
    }

    private suspend fun handle(t: MqttTransport, p: MqttPacket) {
        when (p) {
            is MqttPacket.Publish -> {
                incoming.emit(MqttMessage(p.topic, p.payload, p.qos, p.retain, p.dup))
                if (p.qos == 1) writeLock.withLock { t.send(MqttCodec.encode(MqttPacket.PubAck(p.packetId!!))) }
            }
            is MqttPacket.PubAck -> lock.withLock { pending[p.packetId]?.done?.complete(Unit) }
            is MqttPacket.SubAck -> if (p.returnCodes.any { it == 0x80 }) AppLog.w("mqtt", "broker refused a subscription (packet ${p.packetId})")
            is MqttPacket.UnsubAck, MqttPacket.PingResp -> Unit
            else -> throw MqttError(MqttError.Kind.Protocol, "unexpected packet from broker: $p")
        }
    }

    private fun backoff(failures: Int): Duration {
        val base = options.backoffMin * (1 shl (failures - 1).coerceAtMost(16)).toDouble()
        val capped = if (base > options.backoffMax) options.backoffMax else base
        return capped * (0.8 + random.nextDouble() * 0.4)
    }
}

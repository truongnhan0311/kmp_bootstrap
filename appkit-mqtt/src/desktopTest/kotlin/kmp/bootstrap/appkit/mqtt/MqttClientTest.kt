package kmp.bootstrap.appkit.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Client thật + broker giả qua socket TCP thật. Thời gian thật nhưng ngắn (giãn cách 50 ms). */
class MqttClientTest {
    private val broker = FakeBroker()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<MqttClient>()

    private fun client(id: String, password: (suspend () -> String?)? = null, will: MqttWill? = null, keepAlive: kotlin.time.Duration = 30.seconds) =
        MqttClient(
            MqttOptions(id, username = id, password = password, will = will, keepAlive = keepAlive, backoffMin = 50.milliseconds, backoffMax = 200.milliseconds, connectTimeout = 2.seconds),
            TcpConnector("127.0.0.1", broker.port), scope,
        ).also { clients += it; it.start() }

    @AfterTest fun tearDown() = runBlocking { clients.forEach { runCatching { it.stop() } }; scope.cancel(); broker.close() }

    private fun run(block: suspend () -> Unit) = runBlocking { withTimeout(10.seconds) { block() } }

    @Test fun publishAndReceiveQos0AndQos1() = run {
        val a = client("a"); val b = client("b")
        a.awaitConnected(); b.awaitConnected()
        val got = scope.async { b.subscribe("cam/+/out").take(2).toList() }
        waitUntil { broker.clients.any { it.clientId == "b" && "cam/+/out" in it.subs } }
        a.publish("cam/1/out", "zero", qos = 0)
        a.publish("cam/2/out", "one", qos = 1)                 // treo tới PUBACK
        val msgs = got.await()
        assertEquals(listOf("cam/1/out" to "zero", "cam/2/out" to "one"), msgs.map { it.topic to it.text })
        assertEquals(listOf(0, 1), msgs.map { it.qos })
    }

    @Test fun passwordIsAskedOnEveryConnectAndRefusalIsReported() = run {
        val calls = AtomicInteger()
        broker.auth = { _, pass -> if (pass == "p3") 0 else 4 }
        val c = client("dev", password = { "p" + calls.incrementAndGet() })
        val waiting = c.state.first { it is MqttState.Waiting } as MqttState.Waiting
        assertTrue(waiting.lastError.badCredentials); assertEquals(4, waiting.lastError.code)
        c.awaitConnected()
        assertEquals(listOf("p1", "p2", "p3"), broker.connects().map { it.password })
    }

    @Test fun reconnectsAndResubscribesAfterTheNetworkDrops() = run {
        val pub = client("pub"); val sub = client("sub")
        pub.awaitConnected(); sub.awaitConnected()
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        scope.launch { sub.subscribe("x/#").collect { got += it.text } }
        waitUntil { broker.clients.any { it.clientId == "sub" && it.subs.isNotEmpty() } }
        pub.publish("x/1", "before")
        waitUntil { got.size == 1 }
        broker.kickAll()
        sub.state.first { it is MqttState.Waiting }
        sub.awaitConnected(); pub.awaitConnected()
        waitUntil { broker.clients.any { it.clientId == "sub" && "x/#" in it.subs } }   // đăng ký lại sau khi nối lại
        pub.publish("x/2", "after")
        waitUntil { got.size == 2 }
        assertEquals(listOf("before", "after"), got.toList())
    }

    @Test fun qos1IsResentWithDupAfterReconnectUntilAcked() = run {
        val c = client("c"); c.awaitConnected()
        broker.dropPubAcks = 1                                 // broker nhận nhưng "mất" xác nhận
        val sending = scope.async { c.publish("t/1", "important", qos = 1) }
        waitUntil { broker.publishes().size == 1 }
        assertTrue(sending.isActive, "chưa có PUBACK thì publish chưa xong")
        broker.kickAll()
        sending.await()                                        // nối lại → gửi lại → được xác nhận
        val sent = broker.publishes().filter { it.topic == "t/1" }
        assertEquals(2, sent.size); assertEquals(listOf(false, true), sent.map { it.dup }); assertEquals(sent[0].packetId, sent[1].packetId)
    }

    @Test fun willIsPublishedOnDropButNotOnStop() = run {
        val watcher = client("w"); watcher.awaitConnected()
        val wills = scope.async { watcher.subscribe("presence/+").take(1).toList() }
        waitUntil { broker.clients.any { it.clientId == "w" && it.subs.isNotEmpty() } }
        val clean = client("clean", will = MqttWill("presence/clean", "offline".encodeToByteArray()))
        val crash = client("crash", will = MqttWill("presence/crash", "offline".encodeToByteArray()))
        clean.awaitConnected(); crash.awaitConnected()
        clean.stop()                                           // DISCONNECT → không có will
        delay(200)
        broker.acceptConnections = false                       // "crash" không nối lại được để mọi thứ rõ ràng
        broker.clients.first { it.clientId == "crash" }.kill()
        assertEquals(listOf("presence/crash"), wills.await().map { it.topic })
    }

    @Test fun silentBrokerIsDetectedByKeepAlive() = runBlocking {
        withTimeout(25.seconds) {
            val c = client("k", keepAlive = 1.seconds)
            repeat(3) {                                        // 3 vòng: lý do phải LUÔN là Timeout, không phải lỗi mạng của lệnh đọc
                c.awaitConnected()
                broker.answerPings = false
                val w = c.state.first { it is MqttState.Waiting } as MqttState.Waiting
                assertEquals(MqttError.Kind.Timeout, w.lastError.kind)
                broker.answerPings = true
            }
            c.awaitConnected()
        }
    }

    @Test fun missingConnAckTimesOutAndRetries() = run {
        broker.answerConnect = false
        val c = client("slow")
        val w = c.state.first { it is MqttState.Waiting } as MqttState.Waiting
        assertEquals(MqttError.Kind.Timeout, w.lastError.kind)
        broker.answerConnect = true
        c.awaitConnected()
    }

    @Test fun idleConnectionIsKeptAliveByPings() = run {
        val c = client("idle", keepAlive = 1.seconds); c.awaitConnected()
        delay(3_000)                                           // gấp 2 lần ngưỡng 1,5 giây mà không gửi gì
        assertEquals(MqttState.Connected, c.state.value)
        assertTrue(broker.received.count { it == MqttPacket.PingReq } >= 2)
        assertEquals(1, broker.connects().size, "không bị nối lại")
    }

    @Test fun stopWhileWaitingForConnAckReturnsAtOnce() = run {
        broker.answerConnect = false
        val c = client("halfway")                              // connectTimeout 2 giây
        waitUntil { broker.connects().isNotEmpty() }
        val took = kotlin.time.measureTime { c.stop() }
        assertTrue(took < 1.seconds, "stop() phải đóng kết nối đang chờ CONNACK, không chờ hết hạn (mất $took)")
        assertEquals(MqttState.Stopped, c.state.value)
    }

    @Test fun lastListenerLeavingUnsubscribes() = run {
        val c = client("u"); c.awaitConnected()
        val one = scope.launch { c.subscribe("a/b").collect {} }
        val two = scope.launch { c.subscribe("a/b").collect {} }
        waitUntil { broker.clients.any { "a/b" in it.subs } }
        one.cancel(); delay(150)
        assertTrue(broker.clients.any { "a/b" in it.subs }, "còn người nghe thì giữ đăng ký")
        two.cancel()
        waitUntil { broker.clients.none { "a/b" in it.subs } }
        assertEquals(1, broker.received.filterIsInstance<MqttPacket.Subscribe>().size, "hai người nghe chỉ một lần SUBSCRIBE")
    }

    @Test fun stoppedClientRefusesWorkAndQos0NeedsAConnection() = run {
        broker.acceptConnections = false
        val c = client("s")
        assertFailsWith<MqttNotConnectedException> { c.publish("t", "x", qos = 0) }
        val pending = scope.async { runCatching { c.publish("t", "x", qos = 1) } }
        delay(100)
        c.stop()
        assertIs<MqttNotConnectedException>(pending.await().exceptionOrNull())
        assertEquals(MqttState.Stopped, c.state.value)
        assertFailsWith<MqttNotConnectedException> { c.publish("t", "x") }
    }

    private suspend fun waitUntil(check: () -> Boolean) { while (!check()) delay(10) }
}

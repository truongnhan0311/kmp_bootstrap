package kmp.bootstrap.appkit.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Broker giả trong bộ nhớ: mỗi lần client nối là một FakeLink mới. */
private class FakeLink {
    val toClient = Channel<ByteArray>(Channel.UNLIMITED)
    val fromClient = Channel<ByteArray>(Channel.UNLIMITED)
    private val reader = MqttFrameReader()
    private val buffered = ArrayDeque<MqttPacket>()

    val connection = object : MqttConnection {
        override suspend fun send(bytes: ByteArray) { fromClient.send(bytes) }
        override suspend fun receive(): ByteArray? = toClient.receiveCatching().getOrNull()
        override suspend fun close() { toClient.close() }
    }

    /** Gói kế tiếp client gửi (đợi nếu chưa có). */
    suspend fun next(): MqttPacket {
        while (buffered.isEmpty()) buffered.addAll(reader.feed(fromClient.receive()))
        return buffered.removeFirst()
    }

    /** Gói client đã gửi mà chưa đọc, không đợi. */
    fun drain(): List<MqttPacket> {
        while (true) { val c = fromClient.tryReceive().getOrNull() ?: break; buffered.addAll(reader.feed(c)) }
        return buffered.toList().also { buffered.clear() }
    }

    suspend fun reply(p: MqttPacket) = toClient.send(MqttCodec.encode(p))
    fun drop() { toClient.close() }

    suspend fun acceptConnect(): MqttPacket.Connect {
        val c = next() as MqttPacket.Connect
        reply(MqttPacket.ConnAck(false, 0))
        return c
    }
}

private class FakeBroker {
    val links = Channel<FakeLink>(Channel.UNLIMITED)
    var opened = 0
    val connector = MqttConnector { opened++; FakeLink().also { links.trySend(it) }.connection }
}

class MqttClientTest {
    private val will = MqttWill("cam/presence/dev-1", """{"v":1,"online":false}""".encodeToByteArray(), retain = true)

    private fun TestScope.client(broker: FakeBroker, scope: CoroutineScope = backgroundScope, creds: suspend () -> MqttCredentials = { MqttCredentials("dev-1", "pw") }) =
        MqttClient(
            MqttOptions(clientId = "dev-1", keepAliveSeconds = 60, will = will, credentials = creds,
                reconnect = ReconnectPolicy(maxDelaySeconds = 60, stableResetSeconds = 60, random = Random(42))),
            broker.connector, scope,
        )

    @Test fun connectsWithWillAndCredentials() = runTest {
        val broker = FakeBroker(); val mqtt = client(broker)
        mqtt.start()
        val link = broker.links.receive()
        val c = link.acceptConnect()
        assertEquals("dev-1", c.clientId); assertEquals("dev-1", c.username); assertEquals("pw", c.password)
        assertEquals(will, c.will); assertEquals(60, c.keepAliveSeconds)
        mqtt.state.first { it == MqttState.Connected }
    }

    @Test fun subscribeAndReceiveQos1SendsPubAck() = runTest {
        val broker = FakeBroker(); val mqtt = client(broker)
        mqtt.start()
        val link = broker.links.receive(); link.acceptConnect()
        mqtt.state.first { it == MqttState.Connected }

        val sub = async { mqtt.subscribe("cam/device/in/dev-1") }
        val s = link.next() as MqttPacket.Subscribe
        assertEquals(listOf("cam/device/in/dev-1" to MqttQos.AtLeastOnce), s.topics)
        link.reply(MqttPacket.SubAck(s.packetId, listOf(1)))
        sub.await()

        val msg = async { mqtt.messages.first() }
        runCurrent()
        link.reply(MqttPacket.Publish("cam/device/in/dev-1", "offer".encodeToByteArray(), MqttQos.AtLeastOnce, packetId = 77))
        assertEquals("offer", msg.await().payload.decodeToString())
        assertEquals(MqttPacket.PubAck(77), link.next())
    }

    @Test fun publishQos1WaitsForPubAckAndTimesOut() = runTest {
        val broker = FakeBroker(); val mqtt = client(broker)
        mqtt.start()
        val link = broker.links.receive(); link.acceptConnect()
        mqtt.state.first { it == MqttState.Connected }

        val ok = async { mqtt.publish("cam/device/out/dev-1", "answer") }
        val p = link.next() as MqttPacket.Publish
        assertEquals(MqttQos.AtLeastOnce, p.qos)
        link.reply(MqttPacket.PubAck(p.packetId!!))
        ok.await()

        val lost = async { runCatching { mqtt.publish("cam/device/out/dev-1", "x") } }
        link.next()
        advanceTimeBy(10_001)
        val e = lost.await().exceptionOrNull()
        assertIs<MqttException>(e); assertEquals(MqttException.Reason.Timeout, e.reason)
    }

    @Test fun publishBeforeConnectFails() = runTest {
        val mqtt = client(FakeBroker())
        val e = assertFailsWith<MqttException> { mqtt.publish("t", "x") }
        assertEquals(MqttException.Reason.NotConnected, e.reason)
    }

    @Test fun refusedConnectionWaitsAndRetriesWithFreshCredentials() = runTest {
        val broker = FakeBroker()
        var asked = 0
        val mqtt = client(broker, creds = { asked++; MqttCredentials("dev-1", "pw$asked") })
        mqtt.start()
        val first = broker.links.receive()
        assertEquals("pw1", (first.next() as MqttPacket.Connect).password)
        first.reply(MqttPacket.ConnAck(false, MqttRefusal.BadCredentials.code))
        val waiting = mqtt.state.first { it is MqttState.Waiting } as MqttState.Waiting
        assertTrue(waiting.lastError!!.contains("BadCredentials"))
        assertTrue(waiting.delayMs in 0..1_000, "first retry waits within [0, 1s]")
        assertTrue("pw1" !in waiting.lastError!!, "password never in error")

        val second = broker.links.receive()
        assertEquals("pw2", (second.next() as MqttPacket.Connect).password)
    }

    @Test fun reconnectsAfterDropAndResubscribes() = runTest {
        val broker = FakeBroker(); val mqtt = client(broker)
        mqtt.start()
        val l1 = broker.links.receive(); l1.acceptConnect()
        mqtt.state.first { it == MqttState.Connected }
        val sub = async { mqtt.subscribe("cam/device/in/dev-1") }
        val s1 = l1.next() as MqttPacket.Subscribe; l1.reply(MqttPacket.SubAck(s1.packetId, listOf(1))); sub.await()

        val pendingPublish = async { runCatching { mqtt.publish("out", "x") } }
        l1.next()
        l1.drop()
        val e = pendingPublish.await().exceptionOrNull()
        assertIs<MqttException>(e); assertEquals(MqttException.Reason.ConnectionLost, e.reason)
        assertIs<MqttState.Waiting>(mqtt.state.first { it is MqttState.Waiting })

        val l2 = broker.links.receive(); l2.acceptConnect()
        val s2 = l2.next() as MqttPacket.Subscribe
        assertEquals(listOf("cam/device/in/dev-1" to MqttQos.AtLeastOnce), s2.topics)
        l2.reply(MqttPacket.SubAck(s2.packetId, listOf(1)))
        mqtt.state.first { it == MqttState.Connected }
        assertEquals(2, broker.opened)
    }

    @Test fun missingPingRespTriggersReconnect() = runTest {
        val broker = FakeBroker(); val mqtt = client(broker)
        mqtt.start()
        val l1 = broker.links.receive(); l1.acceptConnect()
        advanceTimeBy(60_001)
        assertEquals(MqttPacket.PingReq, l1.next())
        l1.reply(MqttPacket.PingResp)                    // trả lời lần 1: vẫn nối
        advanceTimeBy(60_000)
        assertEquals(MqttPacket.PingReq, l1.next())      // lần 2 không trả lời
        advanceTimeBy(60_001)
        val w = mqtt.state.first { it is MqttState.Waiting } as MqttState.Waiting
        assertTrue(w.lastError!!.contains("PINGRESP"))
    }

    @Test fun stopSendsDisconnectAndDoesNotReconnect() = runTest {
        val broker = FakeBroker(); val mqtt = client(broker)
        mqtt.start()
        val l1 = broker.links.receive(); l1.acceptConnect()
        mqtt.state.first { it == MqttState.Connected }
        mqtt.stop()
        assertEquals(listOf<MqttPacket>(MqttPacket.Disconnect), l1.drain())
        assertEquals(MqttState.Stopped, mqtt.state.value)
        advanceTimeBy(120_000)
        assertEquals(1, broker.opened)
    }

    @Test fun backoffStaysWithinBounds() {
        val policy = ReconnectPolicy(maxDelaySeconds = 60, random = Random(1))
        repeat(200) {
            assertTrue(policy.delayMs(0) in 0..1_000)
            assertTrue(policy.delayMs(3) in 0..8_000)
            assertTrue(policy.delayMs(10) in 0..60_000)
            assertTrue(policy.delayMs(1_000) in 0..60_000)
        }
    }
}

package kmp.bootstrap.appkit.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VÍ DỤ CHẠY ĐƯỢC của appkit-mqtt (chép vào docs/mqtt.md bằng tools/sync-snippets.mjs). */
class MqttExamples {
    // region: mqtt-setup
    // Một client cho một danh tính (thiết bị hoặc người dùng). credentials và url được hỏi lại ở MỖI lần nối:
    // mật khẩu có ký thời gian hay địa chỉ broker đổi (chuyển shard) đều tự đúng ở lần nối sau.
    fun deviceClient(scope: CoroutineScope, devid: String, sign: () -> String, brokerUrl: () -> String) = MqttClient(
        MqttOptions(
            clientId = devid,
            keepAliveSeconds = 60,
            will = MqttWill("presence/$devid", """{"online":false}""".encodeToByteArray(), retain = true),  // broker phát khi rớt
            credentials = { MqttCredentials(username = devid, password = sign()) },
        ),
        WebSocketConnector(mqttHttpClient()) { brokerUrl() },     // wss://…/mqtt, đi cổng 443
        scope,
    )
    // endregion

    // region: mqtt-use
    suspend fun serve(mqtt: MqttClient, devid: String) = coroutineScope {
        // NGHE TRƯỚC rồi mới nối: `messages` không phát lại, tin đến lúc chưa ai collect là mất.
        val first = async { mqtt.messages.first { it.topic == "in/$devid" } }  // tin QoS 1 nhận được đã tự PUBACK
        mqtt.start()                                               // nối + tự nối lại; theo dõi mqtt.state nếu cần hiện trạng thái
        mqtt.subscribe("in/$devid")                                // được nhớ: tự đăng ký lại sau mỗi lần nối lại
        mqtt.state.first { it == MqttState.Connected }
        mqtt.publish("presence/$devid", """{"online":true}""", retain = true)   // QoS 1: chờ PUBACK, lỗi thì ném MqttException
        mqtt.publish("out/$devid", "got ${first.await().payload.decodeToString()}")
        mqtt.stop()                                                // DISCONNECT êm: broker KHÔNG phát Last Will
    }
    // endregion

    @Test fun runsAgainstFakeBroker() = runTest {
        val links = Channel<ExampleLink>(Channel.UNLIMITED)
        val mqtt = MqttClient(MqttOptions("dev-1", keepAliveSeconds = 0, credentials = { MqttCredentials("dev-1", "sig") }),
            MqttConnector { ExampleLink().also { links.trySend(it) }.conn }, backgroundScope)
        val job = backgroundScope.async { serve(mqtt, "dev-1") }
        val link = links.receive()
        assertTrue(link.read() is MqttPacket.Connect); link.write(MqttPacket.ConnAck(false, 0))
        val sub = link.read() as MqttPacket.Subscribe; link.write(MqttPacket.SubAck(sub.packetId, listOf(1)))
        val pres = link.read() as MqttPacket.Publish; assertTrue(pres.retain); link.write(MqttPacket.PubAck(pres.packetId!!))
        link.write(MqttPacket.Publish("in/dev-1", "offer".encodeToByteArray(), MqttQos.AtLeastOnce, packetId = 5))
        assertEquals(MqttPacket.PubAck(5), link.read())
        val out = link.read() as MqttPacket.Publish; assertEquals("got offer", out.payload.decodeToString()); link.write(MqttPacket.PubAck(out.packetId!!))
        assertEquals(MqttPacket.Disconnect, link.read())
        job.await()
    }

    private class ExampleLink {
        val toClient = Channel<ByteArray>(Channel.UNLIMITED)
        val fromClient = Channel<ByteArray>(Channel.UNLIMITED)
        private val reader = MqttFrameReader(); private val buf = ArrayDeque<MqttPacket>()
        val conn = object : MqttConnection {
            override suspend fun send(bytes: ByteArray) { fromClient.send(bytes) }
            override suspend fun receive() = toClient.receiveCatching().getOrNull()
            override suspend fun close() { toClient.close() }
        }
        suspend fun read(): MqttPacket { while (buf.isEmpty()) buf.addAll(reader.feed(fromClient.receive())); return buf.removeFirst() }
        suspend fun write(p: MqttPacket) = toClient.send(MqttCodec.encode(p))
    }
}


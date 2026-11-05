package kmp.bootstrap.appkit.mqtt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MqttCodecTest {
    private fun hex(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun connectMatchesSpecBytes() {
        // CONNECT, protocol "MQTT" level 4, clean session, keepAlive 60, clientId "a" — tính tay theo MQTT 3.1.1 mục 3.1.
        val bytes = MqttCodec.encode(MqttPacket.Connect(clientId = "a", keepAliveSeconds = 60))
        assertContentEquals(hex(0x10, 0x0D, 0x00, 0x04, 0x4D, 0x51, 0x54, 0x54, 0x04, 0x02, 0x00, 0x3C, 0x00, 0x01, 0x61), bytes)
    }

    @Test fun connectFlagsWithWillAndCredentials() {
        val will = MqttWill("p/1", "off".encodeToByteArray(), MqttQos.AtLeastOnce, retain = true)
        val bytes = MqttCodec.encode(MqttPacket.Connect("c", "u", "pw", 30, true, will))
        // flags: username 0x80 | password 0x40 | will retain 0x20 | will QoS1 0x08 | will 0x04 | clean 0x02 = 0xEE
        assertEquals(0xEE, bytes[9].toInt() and 0xFF)
    }

    @Test fun remainingLengthBoundaries() {
        assertContentEquals(hex(0x00), MqttCodec.encodeRemainingLength(0))
        assertContentEquals(hex(0x7F), MqttCodec.encodeRemainingLength(127))
        assertContentEquals(hex(0x80, 0x01), MqttCodec.encodeRemainingLength(128))
        assertContentEquals(hex(0xFF, 0x7F), MqttCodec.encodeRemainingLength(16_383))
        assertContentEquals(hex(0x80, 0x80, 0x01), MqttCodec.encodeRemainingLength(16_384))
        assertContentEquals(hex(0xFF, 0xFF, 0xFF, 0x7F), MqttCodec.encodeRemainingLength(268_435_455))
        assertFailsWith<IllegalArgumentException> { MqttCodec.encodeRemainingLength(268_435_456) }
    }

    @Test fun everyPacketRoundTrips() {
        val packets = listOf(
            MqttPacket.Connect("dev-1", "dev-1", "1700000000:ab", 60, true, MqttWill("cam/presence/dev-1", """{"online":false}""".encodeToByteArray(), retain = true)),
            MqttPacket.Connect("x", keepAliveSeconds = 0, cleanSession = false),
            MqttPacket.ConnAck(sessionPresent = true, returnCode = 0),
            MqttPacket.ConnAck(sessionPresent = false, returnCode = 4),
            MqttPacket.Publish("a/b", "hi".encodeToByteArray()),
            MqttPacket.Publish("a/b", ByteArray(300) { it.toByte() }, MqttQos.AtLeastOnce, retain = true, dup = true, packetId = 65535),
            MqttPacket.Publish("empty", ByteArray(0), retain = true),
            MqttPacket.PubAck(7),
            MqttPacket.Subscribe(2, listOf("in/dev-1" to MqttQos.AtLeastOnce, "x" to MqttQos.AtMostOnce)),
            MqttPacket.SubAck(2, listOf(1, 0x80)),
            MqttPacket.Unsubscribe(3, listOf("x")),
            MqttPacket.UnsubAck(3),
            MqttPacket.PingReq, MqttPacket.PingResp, MqttPacket.Disconnect,
        )
        for (p in packets) assertEquals(p, MqttCodec.decode(MqttCodec.encode(p)), "round trip of $p")
    }

    @Test fun utf8TopicsSurvive() {
        val p = MqttPacket.Publish("nhà/bếp", "xin chào".encodeToByteArray())
        val back = MqttCodec.decode(MqttCodec.encode(p)) as MqttPacket.Publish
        assertEquals("nhà/bếp", back.topic)
        assertEquals("xin chào", back.payload.decodeToString())
    }

    @Test fun rejectsMalformedInput() {
        assertFailsWith<MqttProtocolException> { MqttCodec.decode(hex(0x30, 0x05, 0x00)) }                 // thiếu byte
        assertFailsWith<MqttProtocolException> { MqttCodec.decode(hex(0xF0, 0x00)) }                       // loại 15
        assertFailsWith<MqttProtocolException> { MqttCodec.decode(hex(0x34, 0x05, 0x00, 0x01, 0x61, 0x00, 0x01)) } // QoS 2
        assertFailsWith<MqttProtocolException> { MqttCodec.decode(hex(0xC0, 0x01, 0x00)) }                 // PINGREQ có byte thừa
        assertFailsWith<IllegalArgumentException> { MqttCodec.encode(MqttPacket.Connect("c", password = "p")) }
    }

    @Test fun frameReaderHandlesSplitAndMergedChunks() {
        val a = MqttCodec.encode(MqttPacket.Publish("t", ByteArray(200) { 1 }, MqttQos.AtLeastOnce, packetId = 9))
        val b = MqttCodec.encode(MqttPacket.PingResp)
        val reader = MqttFrameReader()
        val got = mutableListOf<MqttPacket>()
        (a + b).forEach { got += reader.feed(byteArrayOf(it)) }                 // từng byte một
        assertEquals(listOf(MqttCodec.decode(a), MqttPacket.PingResp), got)
        assertEquals(listOf(MqttCodec.decode(a), MqttPacket.PingResp, MqttPacket.PingResp), MqttFrameReader().feed(a + b + b)) // dồn nhiều gói
    }

    @Test fun frameReaderRejectsOversizedPacket() {
        val big = MqttCodec.encode(MqttPacket.Publish("t", ByteArray(2_000)))
        assertFailsWith<MqttProtocolException> { MqttFrameReader(maxPacketBytes = 1_000).feed(big.copyOfRange(0, 5)) }
    }
}

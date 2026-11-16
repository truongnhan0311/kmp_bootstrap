package kmp.bootstrap.appkit.mqtt

import kmp.bootstrap.appkit.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MqttCodecTest {
    private fun roundTrip(p: MqttPacket) {
        val d = MqttCodec.Decoder(); d.feed(MqttCodec.encode(p))
        assertEquals(p, d.next()); assertNull(d.next())
    }

    @Test fun connectBytesFollowTheSpec() {
        // MQTT 3.1.1 mục 3.1: "MQTT", mức 4, cờ (user+pass+will qos1 retain+clean), keepalive 60, rồi id, will, user, pass.
        val p = MqttPacket.Connect("c1", "u", "p", 60, true, MqttWill("w", byteArrayOf(0x6f), qos = 1, retain = true))
        assertEquals("10" + "1a" + "00044d5154540" + "4" + "ee" + "003c" + "00026331" + "000177" + "00016f" + "000175" + "000170", Hex.encode(MqttCodec.encode(p)))
        roundTrip(p)
    }

    @Test fun everyPacketRoundTrips() {
        listOf(
            MqttPacket.Connect("id"), MqttPacket.ConnAck(true, 5),
            MqttPacket.Publish("a/b", "hi".encodeToByteArray()),
            MqttPacket.Publish("a/b", ByteArray(0), qos = 1, retain = true, dup = true, packetId = 65535),
            MqttPacket.PubAck(7), MqttPacket.Subscribe(8, listOf("a/+" to 1, "b/#" to 0)), MqttPacket.SubAck(8, listOf(1, 0x80)),
            MqttPacket.Unsubscribe(9, listOf("a/+")), MqttPacket.UnsubAck(9),
            MqttPacket.PingReq, MqttPacket.PingResp, MqttPacket.Disconnect,
        ).forEach(::roundTrip)
        assertEquals("c000", Hex.encode(MqttCodec.encode(MqttPacket.PingReq)))
        assertEquals("82", Hex.encode(MqttCodec.encode(MqttPacket.Subscribe(1, listOf("a" to 0)))).take(2), "SUBSCRIBE phải có cờ 0010")
    }

    @Test fun remainingLengthBoundaries() {
        // 127 → 1 byte, 128 → 2 byte, 16383 → 2 byte, 16384 → 3 byte (mục 2.2.3).
        for ((size, lenBytes) in listOf(127 to 1, 128 to 2, 16_383 to 2, 16_384 to 3, 2_097_152 to 4)) {
            val topic = "t"; val payload = ByteArray(size - 3)          // 2 byte độ dài chủ đề + 1 byte chủ đề
            val bytes = MqttCodec.encode(MqttPacket.Publish(topic, payload))
            assertEquals(1 + lenBytes + size, bytes.size, "size $size")
            val d = MqttCodec.Decoder(maxPacket = 4 shl 20); d.feed(bytes)
            assertEquals(payload.size, (d.next() as MqttPacket.Publish).payload.size)
        }
    }

    @Test fun decoderHandlesSplitAndMergedChunks() {
        val a = MqttCodec.encode(MqttPacket.Publish("x/y", "one".encodeToByteArray()))
        val b = MqttCodec.encode(MqttPacket.PubAck(3))
        val all = a + b
        val d = MqttCodec.Decoder()
        for (byte in all) d.feed(byteArrayOf(byte))   // từng byte một
        assertEquals("one", (d.next() as MqttPacket.Publish).payload.decodeToString())
        assertEquals(MqttPacket.PubAck(3), d.next()); assertNull(d.next())
        val d2 = MqttCodec.Decoder(); d2.feed(all + a.copyOf(2))   // hai gói rưỡi
        assertTrue(d2.next() is MqttPacket.Publish); assertTrue(d2.next() is MqttPacket.PubAck); assertNull(d2.next())
    }

    @Test fun malformedInputIsRejected() {
        fun bad(hex: String, max: Int = MqttCodec.DEFAULT_MAX_PACKET) = assertFailsWith<MqttProtocolException> { MqttCodec.Decoder(max).apply { feed(Hex.decode(hex)) }.next() }
        bad("30ffffffff01")            // độ dài còn lại quá 4 byte (và quá lớn)
        bad("c08080808000")            // 5 byte độ dài dù giá trị là 0: chuẩn chỉ cho tối đa 4
        bad("3004000a6162")            // chủ đề dài 10 nhưng gói chỉ có 4 byte
        bad("3600" + "0001" + "61")    // QoS 3
        bad("c100")                    // PINGREQ sai cờ
        bad("f000")                    // loại 15
        bad("30ff7f", max = 100)       // vượt giới hạn kích thước
        bad("100c00064d514973647003000000")      // MQIsdp 3.1 cũ
    }

    @Test fun topicMatching() {
        val yes = listOf("a/b" to "a/b", "a/+" to "a/b", "a/#" to "a/b/c", "a/#" to "a", "#" to "a/b", "+/+" to "a/b", "a/+/c" to "a/x/c")
        val no = listOf("a/b" to "a/c", "a/+" to "a/b/c", "a/+" to "a", "+" to "a/b", "#" to "\$SYS/x", "+/x" to "\$SYS/x", "a/b" to "a/b/")
        yes.forEach { (f, t) -> assertTrue(MqttTopic.matches(f, t), "$f ~ $t") }
        no.forEach { (f, t) -> assertFalse(MqttTopic.matches(f, t), "$f !~ $t") }
        assertTrue(MqttTopic.isValidFilter("a/+/#")); assertFalse(MqttTopic.isValidFilter("a/#/b"))
        assertFalse(MqttTopic.isValidFilter("a/b+")); assertFalse(MqttTopic.isValidFilter(""))
    }
}

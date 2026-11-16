package kmp.bootstrap.appkit.mqtt

/*
 * Gói MQTT 3.1.1 (OASIS, 2014) — chỉ những gói một client cần: CONNECT/CONNACK, PUBLISH/PUBACK (QoS 0 và 1),
 * SUBSCRIBE/SUBACK, UNSUBSCRIBE/UNSUBACK, PINGREQ/PINGRESP, DISCONNECT. Không có QoS 2.
 * Bộ giải mã nhận byte theo từng mẩu bất kỳ (WebSocket/TCP có thể cắt hay gộp gói) và trả từng gói khi đủ.
 */
class MqttProtocolException(message: String) : Exception(message)

/** Lời trăng trối: broker gửi thay khi client rớt mạng mà không kịp DISCONNECT (vd hiện diện "offline"). */
class MqttWill(val topic: String, val payload: ByteArray, val qos: Int = 1, val retain: Boolean = true) {
    init { require(qos in 0..1) { "will qos must be 0 or 1" } }
    override fun equals(other: Any?) = other is MqttWill && topic == other.topic && payload.contentEquals(other.payload) && qos == other.qos && retain == other.retain
    override fun hashCode() = topic.hashCode() * 31 + payload.contentHashCode()
    override fun toString() = "MqttWill($topic, ${payload.size} bytes, qos=$qos, retain=$retain)"
}

sealed interface MqttPacket {
    data class Connect(
        val clientId: String, val username: String? = null, val password: String? = null,
        val keepAliveSeconds: Int = 30, val cleanSession: Boolean = true, val will: MqttWill? = null,
    ) : MqttPacket

    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPacket

    class Publish(
        val topic: String, val payload: ByteArray, val qos: Int = 0, val retain: Boolean = false,
        val dup: Boolean = false, val packetId: Int? = null,
    ) : MqttPacket {
        init {
            require(qos in 0..1) { "only qos 0 and 1 are supported" }
            require((qos == 0) == (packetId == null)) { "qos 1 needs a packet id, qos 0 must not have one" }
        }
        fun copy(dup: Boolean) = Publish(topic, payload, qos, retain, dup, packetId)
        override fun equals(other: Any?) = other is Publish && topic == other.topic && payload.contentEquals(other.payload) &&
            qos == other.qos && retain == other.retain && dup == other.dup && packetId == other.packetId
        override fun hashCode() = topic.hashCode() * 31 + payload.contentHashCode()
        override fun toString() = "Publish($topic, ${payload.size} bytes, qos=$qos, retain=$retain, dup=$dup, id=$packetId)"
    }

    data class PubAck(val packetId: Int) : MqttPacket
    data class Subscribe(val packetId: Int, val filters: List<Pair<String, Int>>) : MqttPacket
    data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPacket
    data class Unsubscribe(val packetId: Int, val filters: List<String>) : MqttPacket
    data class UnsubAck(val packetId: Int) : MqttPacket
    data object PingReq : MqttPacket
    data object PingResp : MqttPacket
    data object Disconnect : MqttPacket
}

object MqttCodec {
    /** Độ dài tối đa của phần sau đầu gói mà bộ giải mã chấp nhận (chống gói khổng lồ). Giới hạn của chuẩn là 268.435.455. */
    const val DEFAULT_MAX_PACKET = 1 shl 20

    fun encode(p: MqttPacket): ByteArray {
        val body = Bytes()
        val first: Int = when (p) {
            is MqttPacket.Connect -> {
                body.str("MQTT"); body.u8(4)
                var flags = 0
                if (p.cleanSession) flags = flags or 0x02
                p.will?.let { flags = flags or 0x04 or (it.qos shl 3) or (if (it.retain) 0x20 else 0) }
                if (p.password != null) flags = flags or 0x40
                if (p.username != null) flags = flags or 0x80
                body.u8(flags); body.u16(p.keepAliveSeconds)
                body.str(p.clientId)
                p.will?.let { body.str(it.topic); body.bin(it.payload) }
                p.username?.let { body.str(it) }
                p.password?.let { body.bin(it.encodeToByteArray()) }
                0x10
            }
            is MqttPacket.ConnAck -> { body.u8(if (p.sessionPresent) 1 else 0); body.u8(p.returnCode); 0x20 }
            is MqttPacket.Publish -> {
                body.str(p.topic); p.packetId?.let { body.u16(it) }; body.raw(p.payload)
                0x30 or (if (p.dup) 0x08 else 0) or (p.qos shl 1) or (if (p.retain) 0x01 else 0)
            }
            is MqttPacket.PubAck -> { body.u16(p.packetId); 0x40 }
            is MqttPacket.Subscribe -> { body.u16(p.packetId); p.filters.forEach { (f, q) -> body.str(f); body.u8(q) }; 0x82 }
            is MqttPacket.SubAck -> { body.u16(p.packetId); p.returnCodes.forEach { body.u8(it) }; 0x90 }
            is MqttPacket.Unsubscribe -> { body.u16(p.packetId); p.filters.forEach { body.str(it) }; 0xA2 }
            is MqttPacket.UnsubAck -> { body.u16(p.packetId); 0xB0 }
            MqttPacket.PingReq -> 0xC0
            MqttPacket.PingResp -> 0xD0
            MqttPacket.Disconnect -> 0xE0
        }
        val out = Bytes()
        out.u8(first); out.varint(body.size); out.raw(body.toByteArray())
        return out.toByteArray()
    }

    /** Giải mã luồng byte. Không an toàn cho nhiều luồng cùng lúc: mỗi kết nối một bộ. */
    class Decoder(private val maxPacket: Int = DEFAULT_MAX_PACKET) {
        private var buf = ByteArray(0)

        fun feed(bytes: ByteArray) { buf += bytes }

        /** Gói kế tiếp, hoặc `null` nếu chưa đủ byte. Dữ liệu sai chuẩn → [MqttProtocolException]. */
        fun next(): MqttPacket? {
            if (buf.isEmpty()) return null
            var len = 0; var mul = 1; var i = 1
            while (true) {
                if (i >= buf.size) return null
                val b = buf[i].toInt() and 0xff
                len += (b and 0x7f) * mul
                i++
                if (b and 0x80 == 0) break
                mul *= 128
                if (i > 4) throw MqttProtocolException("remaining length longer than 4 bytes")
            }
            if (len > maxPacket) throw MqttProtocolException("packet of $len bytes exceeds limit $maxPacket")
            if (buf.size < i + len) return null
            val first = buf[0].toInt() and 0xff
            val body = buf.copyOfRange(i, i + len)
            buf = buf.copyOfRange(i + len, buf.size)
            return parse(first, Reader(body))
        }

        private fun parse(first: Int, r: Reader): MqttPacket {
            val type = first shr 4; val flags = first and 0x0f
            fun expectFlags(want: Int) { if (flags != want) throw MqttProtocolException("bad flags $flags for packet type $type") }
            return when (type) {
                1 -> {
                    expectFlags(0)
                    if (r.str() != "MQTT" || r.u8() != 4) throw MqttProtocolException("only MQTT 3.1.1 is supported")
                    val f = r.u8(); val keepAlive = r.u16(); val clientId = r.str()
                    val will = if (f and 0x04 != 0) MqttWill(r.str(), r.bin(), (f shr 3) and 0x03, f and 0x20 != 0) else null
                    val user = if (f and 0x80 != 0) r.str() else null
                    val pass = if (f and 0x40 != 0) r.bin().decodeToString() else null
                    MqttPacket.Connect(clientId, user, pass, keepAlive, f and 0x02 != 0, will)
                }
                2 -> { expectFlags(0); MqttPacket.ConnAck(r.u8() and 1 == 1, r.u8()) }
                3 -> {
                    val qos = (flags shr 1) and 0x03
                    if (qos > 1) throw MqttProtocolException("qos $qos is not supported")
                    val topic = r.str(); val id = if (qos > 0) r.u16() else null
                    MqttPacket.Publish(topic, r.rest(), qos, flags and 1 == 1, flags and 0x08 != 0, id)
                }
                4 -> { expectFlags(0); MqttPacket.PubAck(r.u16()) }
                8 -> {
                    expectFlags(2); val id = r.u16(); val fs = mutableListOf<Pair<String, Int>>()
                    while (r.hasMore()) fs += r.str() to r.u8()
                    MqttPacket.Subscribe(id, fs)
                }
                9 -> { expectFlags(0); val id = r.u16(); val codes = mutableListOf<Int>(); while (r.hasMore()) codes += r.u8(); MqttPacket.SubAck(id, codes) }
                10 -> { expectFlags(2); val id = r.u16(); val fs = mutableListOf<String>(); while (r.hasMore()) fs += r.str(); MqttPacket.Unsubscribe(id, fs) }
                11 -> { expectFlags(0); MqttPacket.UnsubAck(r.u16()) }
                12 -> { expectFlags(0); MqttPacket.PingReq }
                13 -> { expectFlags(0); MqttPacket.PingResp }
                14 -> { expectFlags(0); MqttPacket.Disconnect }
                else -> throw MqttProtocolException("unsupported packet type $type")
            }
        }
    }

    private class Bytes {
        private var data = ByteArray(64); var size = 0; private set
        private fun ensure(n: Int) { if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n)) }
        fun u8(v: Int) { ensure(1); data[size++] = v.toByte() }
        fun u16(v: Int) { require(v in 0..0xffff) { "value $v does not fit in 16 bits" }; u8(v shr 8); u8(v and 0xff) }
        fun raw(b: ByteArray) { ensure(b.size); b.copyInto(data, size); size += b.size }
        fun bin(b: ByteArray) { u16(b.size); raw(b) }
        fun str(s: String) = bin(s.encodeToByteArray())
        fun varint(v: Int) {
            require(v in 0..268_435_455) { "packet too large" }
            var x = v
            do { var d = x % 128; x /= 128; if (x > 0) d = d or 0x80; u8(d) } while (x > 0)
        }
        fun toByteArray() = data.copyOf(size)
    }

    private class Reader(private val b: ByteArray) {
        private var i = 0
        fun hasMore() = i < b.size
        fun u8(): Int { if (i >= b.size) throw MqttProtocolException("packet truncated"); return b[i++].toInt() and 0xff }
        fun u16() = (u8() shl 8) or u8()
        fun bin(): ByteArray { val n = u16(); if (i + n > b.size) throw MqttProtocolException("packet truncated"); return b.copyOfRange(i, i + n).also { i += n } }
        fun str() = bin().decodeToString()
        fun rest(): ByteArray = b.copyOfRange(i, b.size).also { i = b.size }
    }
}

/** So khớp chủ đề với bộ lọc MQTT (`+` một tầng, `#` mọi tầng còn lại). Chủ đề bắt đầu bằng `$` không khớp ký tự đại diện ở tầng đầu. */
object MqttTopic {
    fun matches(filter: String, topic: String): Boolean {
        if (topic.startsWith("$") && (filter.startsWith("+") || filter.startsWith("#"))) return false
        val f = filter.split('/'); val t = topic.split('/')
        for (i in f.indices) {
            when (f[i]) {
                "#" -> return i == f.lastIndex
                "+" -> if (i >= t.size) return false
                else -> if (i >= t.size || f[i] != t[i]) return false
            }
        }
        return f.size == t.size
    }

    /** Bộ lọc hợp lệ: không rỗng, `#` chỉ đứng một mình ở tầng cuối, `+` đứng một mình trong tầng. */
    fun isValidFilter(filter: String): Boolean {
        if (filter.isEmpty()) return false
        val parts = filter.split('/')
        return parts.withIndex().all { (i, p) ->
            when {
                '#' in p -> p == "#" && i == parts.lastIndex
                '+' in p -> p == "+"
                else -> true
            }
        }
    }
}

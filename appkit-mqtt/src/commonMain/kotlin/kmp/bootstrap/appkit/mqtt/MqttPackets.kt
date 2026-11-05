package kmp.bootstrap.appkit.mqtt

/*
 * Gói tin MQTT 3.1.1 (OASIS) — chỉ phần một client cần: CONNECT/CONNACK, PUBLISH QoS 0/1, PUBACK,
 * SUBSCRIBE/SUBACK, UNSUBSCRIBE/UNSUBACK, PINGREQ/PINGRESP, DISCONNECT. QoS 2 không hỗ trợ (gửi QoS 2 bị chặn bằng require).
 * Mã hoá/giải mã thuần Kotlin, không phụ thuộc mạng, nên test được hết trên JVM.
 */

enum class MqttQos(val code: Int) { AtMostOnce(0), AtLeastOnce(1) }

/** Last Will: broker tự phát khi client mất kết nối mà không gửi DISCONNECT. */
class MqttWill(val topic: String, val payload: ByteArray, val qos: MqttQos = MqttQos.AtLeastOnce, val retain: Boolean = false) {
    override fun equals(other: Any?) = other is MqttWill && topic == other.topic && payload.contentEquals(other.payload) &&
        qos == other.qos && retain == other.retain
    override fun hashCode() = topic.hashCode() * 31 + payload.contentHashCode()
    override fun toString() = "MqttWill(topic=$topic, bytes=${payload.size}, qos=$qos, retain=$retain)"
}

sealed class MqttPacket {
    data class Connect(
        val clientId: String,
        val username: String? = null,
        val password: String? = null,
        val keepAliveSeconds: Int = 60,
        val cleanSession: Boolean = true,
        val will: MqttWill? = null,
    ) : MqttPacket()

    /** returnCode 0 = chấp nhận; 1..5 = từ chối (xem [MqttRefusal]). */
    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPacket()

    class Publish(
        val topic: String,
        val payload: ByteArray,
        val qos: MqttQos = MqttQos.AtMostOnce,
        val retain: Boolean = false,
        val dup: Boolean = false,
        val packetId: Int? = null,
    ) : MqttPacket() {
        override fun equals(other: Any?) = other is Publish && topic == other.topic && payload.contentEquals(other.payload) &&
            qos == other.qos && retain == other.retain && dup == other.dup && packetId == other.packetId
        override fun hashCode() = topic.hashCode() * 31 + payload.contentHashCode()
        override fun toString() = "Publish(topic=$topic, bytes=${payload.size}, qos=$qos, retain=$retain, dup=$dup, id=$packetId)"
    }

    data class PubAck(val packetId: Int) : MqttPacket()
    data class Subscribe(val packetId: Int, val topics: List<Pair<String, MqttQos>>) : MqttPacket()
    /** Mỗi mã: 0/1 = QoS được cấp, 0x80 = từ chối. */
    data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPacket()
    data class Unsubscribe(val packetId: Int, val topics: List<String>) : MqttPacket()
    data class UnsubAck(val packetId: Int) : MqttPacket()
    data object PingReq : MqttPacket()
    data object PingResp : MqttPacket()
    data object Disconnect : MqttPacket()
}

enum class MqttRefusal(val code: Int) {
    UnacceptableProtocol(1), IdentifierRejected(2), ServerUnavailable(3), BadCredentials(4), NotAuthorized(5);
    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

class MqttProtocolException(message: String) : Exception(message)

object MqttCodec {
    private const val MAX_REMAINING = 268_435_455

    fun encode(packet: MqttPacket): ByteArray {
        val body = Bytes()
        val header: Int = when (packet) {
            is MqttPacket.Connect -> {
                body.string("MQTT"); body.byte(4)                                    // protocol name + level 4 (3.1.1)
                var flags = 0
                if (packet.cleanSession) flags = flags or 0x02
                packet.will?.let { flags = flags or 0x04 or (it.qos.code shl 3) or (if (it.retain) 0x20 else 0) }
                if (packet.password != null) flags = flags or 0x40
                if (packet.username != null) flags = flags or 0x80
                require(packet.password == null || packet.username != null) { "MQTT 3.1.1: password requires username" }
                require(packet.keepAliveSeconds in 0..65535) { "keepAlive out of range" }
                body.byte(flags); body.u16(packet.keepAliveSeconds)
                body.string(packet.clientId)
                packet.will?.let { body.string(it.topic); body.binary(it.payload) }
                packet.username?.let { body.string(it) }
                packet.password?.let { body.string(it) }
                0x10
            }
            is MqttPacket.ConnAck -> { body.byte(if (packet.sessionPresent) 1 else 0); body.byte(packet.returnCode); 0x20 }
            is MqttPacket.Publish -> {
                require(packet.qos == MqttQos.AtMostOnce || packet.packetId != null) { "QoS 1 publish needs packetId" }
                body.string(packet.topic)
                if (packet.qos != MqttQos.AtMostOnce) body.u16(packet.packetId!!)
                body.raw(packet.payload)
                0x30 or (if (packet.dup) 0x08 else 0) or (packet.qos.code shl 1) or (if (packet.retain) 1 else 0)
            }
            is MqttPacket.PubAck -> { body.u16(packet.packetId); 0x40 }
            is MqttPacket.Subscribe -> {
                require(packet.topics.isNotEmpty()) { "SUBSCRIBE needs at least one topic" }
                body.u16(packet.packetId)
                packet.topics.forEach { (t, q) -> body.string(t); body.byte(q.code) }
                0x82
            }
            is MqttPacket.SubAck -> { body.u16(packet.packetId); packet.returnCodes.forEach { body.byte(it) }; 0x90 }
            is MqttPacket.Unsubscribe -> {
                require(packet.topics.isNotEmpty()) { "UNSUBSCRIBE needs at least one topic" }
                body.u16(packet.packetId); packet.topics.forEach { body.string(it) }; 0xA2
            }
            is MqttPacket.UnsubAck -> { body.u16(packet.packetId); 0xB0 }
            MqttPacket.PingReq -> 0xC0
            MqttPacket.PingResp -> 0xD0
            MqttPacket.Disconnect -> 0xE0
        }
        val payload = body.toByteArray()
        val out = Bytes()
        out.byte(header)
        out.raw(encodeRemainingLength(payload.size))
        out.raw(payload)
        return out.toByteArray()
    }

    fun encodeRemainingLength(length: Int): ByteArray {
        require(length in 0..MAX_REMAINING) { "remaining length out of range: $length" }
        val out = Bytes()
        var x = length
        do {
            var digit = x % 128
            x /= 128
            if (x > 0) digit = digit or 0x80
            out.byte(digit)
        } while (x > 0)
        return out.toByteArray()
    }

    /** Giải mã MỘT gói đầy đủ (header + remaining length + body). */
    fun decode(frame: ByteArray): MqttPacket {
        val r = Reader(frame)
        val first = r.u8()
        val length = r.remainingLength()
        if (r.left() != length) throw MqttProtocolException("length mismatch: header says $length, got ${r.left()}")
        val type = first ushr 4
        val flags = first and 0x0F
        val p: MqttPacket = when (type) {
            1 -> {
                if (r.string() != "MQTT" || r.u8() != 4) throw MqttProtocolException("not MQTT 3.1.1 CONNECT")
                val f = r.u8(); val keepAlive = r.u16(); val clientId = r.string()
                val will = if (f and 0x04 != 0) {
                    val topic = r.string(); val data = r.binary()
                    MqttWill(topic, data, qosOf((f ushr 3) and 0x03), f and 0x20 != 0)
                } else null
                val user = if (f and 0x80 != 0) r.string() else null
                val pass = if (f and 0x40 != 0) r.string() else null
                MqttPacket.Connect(clientId, user, pass, keepAlive, f and 0x02 != 0, will)
            }
            2 -> MqttPacket.ConnAck(r.u8() and 0x01 == 1, r.u8())
            3 -> {
                val qos = qosOf((flags ushr 1) and 0x03)
                val topic = r.string()
                val id = if (qos != MqttQos.AtMostOnce) r.u16() else null
                MqttPacket.Publish(topic, r.rest(), qos, flags and 0x01 == 1, flags and 0x08 != 0, id)
            }
            4 -> MqttPacket.PubAck(r.u16())
            8 -> {
                val id = r.u16(); val topics = mutableListOf<Pair<String, MqttQos>>()
                while (r.left() > 0) topics += r.string() to qosOf(r.u8())
                MqttPacket.Subscribe(id, topics)
            }
            9 -> { val id = r.u16(); val codes = mutableListOf<Int>(); while (r.left() > 0) codes += r.u8(); MqttPacket.SubAck(id, codes) }
            10 -> { val id = r.u16(); val topics = mutableListOf<String>(); while (r.left() > 0) topics += r.string(); MqttPacket.Unsubscribe(id, topics) }
            11 -> MqttPacket.UnsubAck(r.u16())
            12 -> MqttPacket.PingReq
            13 -> MqttPacket.PingResp
            14 -> MqttPacket.Disconnect
            else -> throw MqttProtocolException("unsupported packet type $type")
        }
        if (r.left() != 0) throw MqttProtocolException("trailing bytes in packet type $type")
        return p
    }

    private fun qosOf(code: Int) = when (code) {
        0 -> MqttQos.AtMostOnce
        1 -> MqttQos.AtLeastOnce
        else -> throw MqttProtocolException("QoS $code not supported")
    }

    private class Reader(val b: ByteArray) {
        var i = 0
        fun left() = b.size - i
        fun u8(): Int { if (i >= b.size) throw MqttProtocolException("truncated packet"); return b[i++].toInt() and 0xFF }
        fun u16() = (u8() shl 8) or u8()
        fun binary(): ByteArray { val n = u16(); if (left() < n) throw MqttProtocolException("truncated field"); return b.copyOfRange(i, i + n).also { i += n } }
        fun string() = binary().decodeToString()
        fun rest() = b.copyOfRange(i, b.size).also { i = b.size }
        fun remainingLength(): Int {
            var mul = 1; var value = 0
            for (k in 0 until 4) {
                val d = u8()
                value += (d and 0x7F) * mul
                if (d and 0x80 == 0) return value
                mul *= 128
            }
            throw MqttProtocolException("malformed remaining length")
        }
    }
}

/**
 * Gom byte nhận được thành từng gói đầy đủ. WebSocket/TCP có thể cắt một gói làm nhiều mảnh hoặc dồn nhiều gói vào một mảnh,
 * nên KHÔNG được coi mỗi lần nhận là một gói.
 */
class MqttFrameReader(private val maxPacketBytes: Int = 1_048_576) {
    private var buf = ByteArray(0)

    fun feed(chunk: ByteArray): List<MqttPacket> {
        buf += chunk
        val out = mutableListOf<MqttPacket>()
        while (true) {
            val total = completeLength() ?: break
            out += MqttCodec.decode(buf.copyOfRange(0, total))
            buf = buf.copyOfRange(total, buf.size)
        }
        return out
    }

    /** Độ dài gói đầu tiên nếu đã nhận đủ; null nếu chưa đủ. */
    private fun completeLength(): Int? {
        if (buf.size < 2) return null
        var mul = 1; var value = 0; var idx = 1
        while (true) {
            if (idx >= buf.size) return null
            if (idx > 4) throw MqttProtocolException("malformed remaining length")
            val d = buf[idx].toInt() and 0xFF
            value += (d and 0x7F) * mul
            idx++
            if (d and 0x80 == 0) break
            mul *= 128
        }
        val total = idx + value
        if (total > maxPacketBytes) throw MqttProtocolException("packet too large: $total bytes")
        return if (buf.size >= total) total else null
    }
}

internal class Bytes {
    private var data = ByteArray(64)
    private var size = 0
    private fun ensure(n: Int) { if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n)) }
    fun byte(v: Int) { ensure(1); data[size++] = v.toByte() }
    fun u16(v: Int) { byte(v ushr 8 and 0xFF); byte(v and 0xFF) }
    fun raw(b: ByteArray) { ensure(b.size); b.copyInto(data, size); size += b.size }
    fun binary(b: ByteArray) { require(b.size <= 65535) { "field longer than 65535 bytes" }; u16(b.size); raw(b) }
    fun string(s: String) = binary(s.encodeToByteArray())
    fun toByteArray() = data.copyOf(size)
}

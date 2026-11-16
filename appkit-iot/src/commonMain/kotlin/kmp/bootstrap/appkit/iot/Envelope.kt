package kmp.bootstrap.appkit.iot

import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/*
 * Khung thông điệp kiểu Tuya cho kênh điều khiển (MQTT, DataChannel): phần `header` cố định để định tuyến và chống trùng,
 * phần `msg` là JSON tự do theo `type`. Tên trường giống hệt server Go (`sessionid`, `trace_id`, `security_level`);
 * trường null không được ghi ra, như `omitempty` của Go.
 *
 *   {"header":{"v":1,"id":"m1","from":"api","to":"dev123","sessionid":"s1","type":"offer","ts":1763280000000},"msg":{...}}
 */
@Serializable
data class EnvelopeHeader(
    val v: Int = 1,
    val id: String? = null,
    val from: String,
    val to: String,
    @SerialName("sessionid") val sessionId: String? = null,
    val type: String,
    @SerialName("trace_id") val traceId: String? = null,
    /** Mili-giây Unix lúc gửi. */
    val ts: Long,
    @SerialName("security_level") val securityLevel: Int? = null,
)

@Serializable
data class Envelope(val header: EnvelopeHeader, val msg: JsonObject = JsonObject(emptyMap())) {
    companion object {
        /** Tạo thông điệp mới, `ts` = bây giờ. */
        fun of(type: String, from: String, to: String, msg: JsonObject = JsonObject(emptyMap()), id: String? = null, sessionId: String? = null, traceId: String? = null) =
            Envelope(EnvelopeHeader(id = id, from = from, to = to, sessionId = sessionId, type = type, traceId = traceId, ts = Clock.System.now().toEpochMilliseconds()), msg)
    }
}

/** Kết quả đọc một thông điệp: [Ok] hoặc [Invalid] kèm lý do (tiếng Anh, cho log). Không bao giờ ném lỗi với dữ liệu lạ. */
sealed interface EnvelopeResult {
    data class Ok(val envelope: Envelope) : EnvelopeResult
    data class Invalid(val reason: String) : EnvelopeResult
}

object EnvelopeCodec {
    /** Thông điệp dài hơn thế này bị từ chối trước khi phân tích (chống dữ liệu khổng lồ). */
    const val MAX_BYTES = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    fun encode(envelope: Envelope): String = json.encodeToString(Envelope.serializer(), envelope)

    /** Đọc và kiểm: phiên bản được hỗ trợ, `from`/`to`/`type` không rỗng, `ts` dương, kích thước ≤ [maxBytes]. */
    fun decode(text: String, supportedVersions: Set<Int> = setOf(1), maxBytes: Int = MAX_BYTES): EnvelopeResult {
        if (text.length > maxBytes) return EnvelopeResult.Invalid("message larger than $maxBytes bytes")
        val e = try { json.decodeFromString(Envelope.serializer(), text) } catch (ex: Exception) {
            return EnvelopeResult.Invalid("not an envelope: ${ex::class.simpleName}")
        }
        val h = e.header
        return when {
            h.v !in supportedVersions -> EnvelopeResult.Invalid("unsupported version ${h.v}")
            h.from.isBlank() || h.to.isBlank() -> EnvelopeResult.Invalid("missing from/to")
            h.type.isBlank() -> EnvelopeResult.Invalid("missing type")
            h.ts <= 0 -> EnvelopeResult.Invalid("missing ts")
            else -> EnvelopeResult.Ok(e)
        }
    }
}

/**
 * Bỏ thông điệp trùng (QoS 1 của MQTT có thể giao một tin hai lần): [firstSeen] trả `true` lần đầu thấy một `id`,
 * `false` nếu đã thấy trong [ttl]. Giữ tối đa [capacity] id, đầy thì quên id cũ nhất. Thông điệp không có id thì luôn `true`.
 */
class DuplicateFilter(
    private val capacity: Int = 1024,
    private val ttl: Duration = 10.minutes,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val seen = LinkedHashMap<String, Long>()

    fun firstSeen(id: String?): Boolean {
        if (id.isNullOrEmpty()) return true
        val now = nowMillis()
        val cutoff = now - ttl.inWholeMilliseconds
        val it = seen.entries.iterator()
        while (it.hasNext()) { if (it.next().value < cutoff) it.remove() else break }   // thứ tự chèn = thứ tự thời gian
        if (seen.containsKey(id)) return false
        seen[id] = now
        if (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }
}

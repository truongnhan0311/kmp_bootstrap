package kmp.bootstrap.appkit

import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Gom log của AppLog (file của RotatingFileSink) thành gói gửi cho người hỗ trợ / máy chủ khi được yêu cầu:
 *
 *     val bundle = LogExport.bundle(fileSink.readAll(), LogExport.Options(minLevel = LogLevel.Info, sinceMillis = now - 30.minutes))
 *     // bundle.gzip: NDJSON nén gzip, bundle.sha256: hex, bundle.lines: số dòng → tải lên bằng LogUploader (appkit-network)
 *
 * Mỗi dòng NDJSON: {"ts":<ms>,"lvl":"info","comp":"<tag>","msg":"…"}, theo thứ tự thời gian.
 * Che thêm trước khi gửi (log trên đĩa đã qua Redactor): email → [email], IPv4 → giữ /24 (203.0.113.x), IPv6 → giữ /48.
 * Quá [Options.maxBytes] (trước khi nén) thì bỏ dòng CŨ, giữ dòng mới nhất, và báo [LogBundle.truncated].
 */
class LogBundle(val gzip: ByteArray, val sha256: String, val lines: Int, val rawBytes: Int, val truncated: Boolean)

object LogExport {
    data class Options(
        val minLevel: LogLevel = LogLevel.Info,
        /** Chỉ lấy dòng từ thời điểm này (mili-giây Unix); `null` = tất cả. */
        val sinceMillis: Long? = null,
        val maxBytes: Int = 5 * 1024 * 1024,
    )

    private val lineRe = Regex("""^(\S+) ([DIWE])/([^:]*): (.*)$""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val ipv6 = Regex("""\b([0-9a-fA-F]{1,4}):([0-9a-fA-F]{1,4}):([0-9a-fA-F]{1,4})(?::[0-9a-fA-F]{0,4}){2,5}\b""")

    /** Che email và phần cuối địa chỉ IP. */
    fun scrub(text: String): String {
        var out = email.replace(text, "[email]")
        out = ipv4.replace(out) { m -> if (m.groupValues.drop(1).all { (it.toIntOrNull() ?: 999) <= 255 }) "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}.x" else m.value }
        out = ipv6.replace(out) { m -> "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}::x" }
        return out
    }

    /** NDJSON (chưa nén) từ nội dung file log; dòng không đúng dạng bị bỏ. */
    fun ndjson(logText: String, options: Options = Options()): List<String> {
        data class Row(val ts: Long, val line: String)
        val rows = logText.lineSequence().mapNotNull { raw ->
            val m = lineRe.matchEntire(raw.trimEnd('\r')) ?: return@mapNotNull null
            val ts = runCatching { Instant.parse(m.groupValues[1]).toEpochMilliseconds() }.getOrNull() ?: return@mapNotNull null
            val level = LogLevel.entries.first { it.label == m.groupValues[2] }
            if (level.ordinal < options.minLevel.ordinal) return@mapNotNull null
            if (options.sinceMillis != null && ts < options.sinceMillis) return@mapNotNull null
            val json = buildJsonObject {
                put("ts", ts); put("lvl", level.name.lowercase()); put("comp", m.groupValues[3]); put("msg", scrub(m.groupValues[4]))
            }
            Row(ts, json.toString())
        }.toList()
        return rows.sortedBy { it.ts }.map { it.line }   // ổn định: cùng ms giữ thứ tự gặp
    }

    suspend fun bundle(logText: String, options: Options = Options()): LogBundle {
        val all = ndjson(logText, options)
        var size = 0
        val kept = ArrayList<String>()
        for (line in all.asReversed()) {                       // giữ dòng MỚI nhất
            val n = line.encodeToByteArray().size + 1
            if (size + n > options.maxBytes) break
            kept += line; size += n
        }
        kept.reverse()
        val raw = kept.joinToString("") { it + "\n" }.encodeToByteArray()
        val gz = Gzip.compress(raw)
        return LogBundle(gz, Hex.encode(AppCrypto.sha256(gz)), kept.size, raw.size, kept.size < all.size)
    }
}

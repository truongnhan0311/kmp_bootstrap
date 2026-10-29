package kmp.bootstrap.appkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/*
 * Log dùng ở bất kỳ đâu:   AppLog.i("auth", "session refreshed")
 * Bật một lần lúc khởi động:  AppLog.install(ConsoleSink(), RotatingFileSink(platformLogDir("my-app")))
 * Dòng log luôn bằng TIẾNG ANH cố định (không theo ngôn ngữ người dùng) để grep/đối chiếu được giữa các máy.
 * File log tự xoay vòng (xem RotatingFileSink) nên không bao giờ phình vô hạn trên máy người dùng.
 */

enum class LogLevel(val label: String) { Debug("D"), Info("I"), Warn("W"), Error("E") }

interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

object AppLog {
    private var sinks: List<LogSink> = emptyList()
    var minLevel: LogLevel = LogLevel.Debug

    fun install(vararg sinks: LogSink) { this.sinks = sinks.toList() }

    fun d(tag: String, message: String) = log(LogLevel.Debug, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.Info, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.Warn, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.Error, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        sinks.forEach { runCatching { it.write(level, tag, message, error) } } // log hỏng không bao giờ làm app hỏng
    }
}

/**
 * Che thứ không bao giờ được nằm trong file log: mật khẩu, token, khoá API, header Authorization.
 *
 * Để TRA LOG (biết hai dòng nói về cùng một token), token/khoá giữ lại [TAIL] ký tự cuối: `[redacted…c123x]`.
 * Hai ngoại lệ có chủ ý: (1) MẬT KHẨU luôn che hẳn — 5 ký tự cuối của mật khẩu là một phần mật khẩu; (2) giá trị ngắn
 * (< [MIN_FOR_TAIL] ký tự) che hẳn, vì với chuỗi ngắn 5 ký tự cuối đã là phần lớn giá trị.
 */
object Redactor {
    const val TAIL = 5
    const val MIN_FOR_TAIL = 16

    private val passwordRule = Regex("(?i)(\"?(?:password|passwd)\"?\\s*[:=]\\s*\"?)([^\"\\s,}&]+)")
    private val tokenRules = listOf(
        Regex("(?i)(\"?(?:refresh_token|access_token|token|secret|api_key)\"?\\s*[:=]\\s*\"?)([^\"\\s,}&]+)"),
        Regex("(?i)(authorization\\s*[:=]\\s*(?:bearer\\s+)?)([^\\s\"]+)"),
        Regex("(?i)(bearer\\s+)([A-Za-z0-9._~+/=-]{8,})"),
    )

    /** `[redacted]` hoặc `[redacted…xxxxx]` (5 ký tự cuối, chỉ khi đủ dài). */
    fun mask(value: String): String =
        if (value.length >= MIN_FOR_TAIL && !value.startsWith("[redacted")) "[redacted…${value.takeLast(TAIL)}]" else "[redacted]"

    fun redact(text: String): String {
        var out = passwordRule.replace(text) { it.groupValues[1] + "[redacted]" }
        for (rule in tokenRules) out = rule.replace(out) { it.groupValues[1] + mask(it.groupValues[2]) }
        return out
    }
}

/** In ra console của nền tảng (logcat / NSLog / stdout). */
class ConsoleSink : LogSink {
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) =
        platformConsole(level, tag, if (error == null) message else "$message — ${error::class.simpleName}: ${error.message}")
}
expect fun platformConsole(level: LogLevel, tag: String, message: String)

/** Thư mục log theo nền tảng (Android: filesDir/logs · iOS: Library/Caches/logs · desktop: ~/.appkit/logs). */
expect fun platformLogDir(appName: String): Path

/**
 * Ghi log ra file và TỰ XOAY VÒNG: khi file hiện tại vượt [maxBytes] thì đổi `app.log` → `app.log.1` → `app.log.2`…,
 * giữ [keepFiles] file cũ (cái cũ nhất bị xoá), và xoá file quá [maxAgeMillis] (mặc định 15 ngày; null = không xoá theo tuổi).
 * Ghi qua một hàng đợi một luồng nên thứ tự đúng và không cần khoá; mọi dòng đều qua [redact] trước khi xuống đĩa.
 */
class RotatingFileSink(
    private val dir: Path,
    private val baseName: String = "app",
    private val maxBytes: Long = 1_000_000,
    private val keepFiles: Int = 5,
    private val maxAgeMillis: Long? = 15L * 24 * 3600 * 1000,
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val redact: (String) -> String = Redactor::redact,
) : LogSink {
    private sealed interface Job
    private class Line(val text: String) : Job
    private class Flush(val done: CompletableDeferred<Unit>) : Job

    private val queue = Channel<Job>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val current: Path get() = dir / "$baseName.log"

    init {
        fs.createDirectories(dir)
        scope.launch {
            for (job in queue) when (job) {
                is Line -> runCatching { append(job.text) }
                is Flush -> job.done.complete(Unit)
            }
        }
    }

    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val stamp = Instant.fromEpochMilliseconds(now()).toString()
        val tail = if (error == null) "" else " | ${error::class.simpleName}: ${error.message}"
        queue.trySend(Line("$stamp ${level.label}/$tag: ${redact(message + tail).replace('\n', ' ')}\n"))
    }

    /** Chờ mọi dòng đã gửi được ghi xong (dùng khi thoát app hoặc trong test). */
    suspend fun flush() { val d = CompletableDeferred<Unit>(); queue.send(Flush(d)); d.await() }

    private fun append(line: String) {
        val size = if (fs.exists(current)) fs.metadata(current).size ?: 0L else 0L
        if (size >= maxBytes) rotate()
        fs.appendingSink(current).buffer().use { it.writeUtf8(line) }
        pruneOld()
    }

    private fun rotate() {
        val oldest = dir / "$baseName.log.$keepFiles"
        if (fs.exists(oldest)) fs.delete(oldest)
        for (i in keepFiles - 1 downTo 1) {
            val from = dir / "$baseName.log.$i"
            if (fs.exists(from)) fs.atomicMove(from, dir / "$baseName.log.${i + 1}")
        }
        if (fs.exists(current)) fs.atomicMove(current, dir / "$baseName.log.1")
    }

    private fun pruneOld() {
        val limit = maxAgeMillis ?: return
        for (i in 1..keepFiles) {
            val f = dir / "$baseName.log.$i"
            val modified = if (fs.exists(f)) fs.metadataOrNull(f)?.lastModifiedAtMillis else null
            if (modified != null && now() - modified > limit) fs.delete(f)
        }
    }

    /** Đọc lại log hiện tại + các file cũ (mới nhất trước) — để màn "gửi log cho người hỗ trợ". */
    fun readAll(): String = buildString {
        for (f in listOf(current) + (1..keepFiles).map { dir / "$baseName.log.$it" })
            if (fs.exists(f)) append(fs.source(f).buffer().use { it.readUtf8() })
    }
}


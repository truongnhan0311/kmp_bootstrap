package kmp.bootstrap.appkit

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Tải gói log (LogExport.bundle của appkit-core) lên URL có chữ ký do máy chủ cấp (S3/GCS/R2… presigned PUT):
 *
 *     val bundle = LogExport.bundle(fileSink.readAll(), LogExport.Options(sinceMillis = since, maxBytes = maxBytes))
 *     when (val r = LogUploader().upload(bundle, uploadUrl)) {
 *         is LogUploadResult.Done -> api.post("/logs/requests/$id/complete", Complete(true, r.sizeBytes, r.sha256))
 *         is LogUploadResult.Failed -> api.post("/logs/requests/$id/complete", Complete(false, error = r.code))
 *     }
 *
 * Không gửi Authorization (chữ ký nằm trong URL) và KHÔNG ghi URL vào log (chữ ký là bí mật; chỉ ghi tên máy).
 * Chỉ nhận https (trừ localhost để test). PUT cùng URL không tạo bản trùng nên lỗi mạng / 5xx được thử lại [retries] lần.
 */
sealed interface LogUploadResult {
    data class Done(val sizeBytes: Int, val sha256: String, val lines: Int, val truncated: Boolean) : LogUploadResult
    /** [code]: `no_logs` (không có dòng nào, không tải) hoặc `upload_failed`. [detail] tiếng Anh, cho log. */
    data class Failed(val code: String, val detail: String) : LogUploadResult
}

class LogUploader(
    engine: HttpClientEngine? = null,
    private val retries: Int = 1,
    private val backoffMs: Long = 1_000,
    timeoutMs: Long = 60_000,
) {
    private val http: HttpClient = (if (engine != null) HttpClient(engine) { setup(timeoutMs) } else HttpClient { setup(timeoutMs) })

    private fun io.ktor.client.HttpClientConfig<*>.setup(timeoutMs: Long) {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = timeoutMs; connectTimeoutMillis = 15_000 }
    }

    suspend fun upload(bundle: LogBundle, uploadUrl: String, contentType: String = "application/gzip"): LogUploadResult {
        val url = runCatching { Url(uploadUrl) }.getOrNull()
        require(url != null && (url.protocol.name == "https" || url.host in setOf("localhost", "127.0.0.1"))) { "upload url must be https" }
        if (bundle.lines == 0) return LogUploadResult.Failed("no_logs", "nothing to upload")
        var attempt = 0
        while (true) {
            val outcome: String? = try {
                val r = http.put(url) { contentType(ContentType.parse(contentType)); setBody(bundle.gzip) }
                when {
                    r.status.isSuccess() -> null
                    r.status.value >= 500 || r.status.value == 429 -> "HTTP ${r.status.value}"
                    else -> return failed(url, "HTTP ${r.status.value}")        // 4xx: URL hết hạn / sai chữ ký, thử lại vô ích
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e::class.simpleName ?: "network error"
            }
            if (outcome == null) {
                AppLog.i("logs", "uploaded ${bundle.gzip.size} bytes (${bundle.lines} lines) to ${url.host}")
                return LogUploadResult.Done(bundle.gzip.size, bundle.sha256, bundle.lines, bundle.truncated)
            }
            if (attempt++ >= retries) return failed(url, outcome)
            delay(backoffMs * attempt)
        }
    }

    private fun failed(url: Url, detail: String): LogUploadResult.Failed {
        AppLog.w("logs", "upload to ${url.host} failed: $detail")
        return LogUploadResult.Failed("upload_failed", detail)
    }
}

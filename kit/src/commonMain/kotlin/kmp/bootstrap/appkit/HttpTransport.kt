package kmp.bootstrap.appkit

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

data class RawResponse(val status: Int, val body: String, val requestId: String?)

/**
 * Tầng GỬI REQUEST thuần: tên miền dự phòng, header `X-App-Id`, timeout, log. KHÔNG biết đăng nhập/token gì cả.
 * Dùng trực tiếp cho API công khai; [AuthedClient] bọc nó để thêm phiên đăng nhập. Tách hai lớp theo kiểu ghép (composition)
 * chứ không kế thừa: muốn thêm một lớp khác (cache, ký request, đo thời gian…) thì bọc [HttpTransport] như [AuthedClient] đã làm,
 * không phải sửa lớp này hay lớp kia.
 */
class HttpTransport(
    private val failover: Failover,
    private val appId: String,
    engine: HttpClientEngine? = null,
    /** Số lần thử lại tự động cho request GET gặp lỗi tạm (0 = tắt). */
    private val getRetries: Int = 1,
    private val backoffMs: Long = 400,
    private val requestTimeoutMs: Long = 30_000,
) {
    constructor(baseUrl: String, appId: String, engine: HttpClientEngine? = null, getRetries: Int = 1, backoffMs: Long = 400, requestTimeoutMs: Long = 30_000) :
        this(Failover(listOf(baseUrl)), appId, engine, getRetries, backoffMs, requestTimeoutMs)

    private val http: HttpClient = if (engine != null) HttpClient(engine) { configure() } else HttpClient { configure() }

    private fun HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = requestTimeoutMs; connectTimeoutMillis = 15_000 }
        defaultRequest { header("X-App-Id", appId) }
    }

    /**
     * Gửi một request. Tự làm ba việc:
     *  1. Chưa tới được máy chủ → thử địa chỉ dự phòng.
     *  2. Request ĐỌC (GET) gặp timeout / lỗi 5xx / 429 → thử lại tối đa [getRetries] lần, nghỉ tăng dần. Request GHI không bao giờ tự thử lại
     *     (server có thể đã nhận — thử lại sẽ ghi trùng).
     *  3. Lỗi mạng/timeout còn lại ném [ApiError] với `kind` Network / Timeout (không bao giờ ném lỗi thô của engine).
     * Phản hồi có mã HTTP (kể cả 404/500) KHÔNG ném lỗi ở đây — trả về [RawResponse] để lớp trên quyết định.
     */
    suspend fun execute(method: String, path: String, body: JsonObject? = null, bearer: String? = null, timeoutMs: Long? = null): RawResponse {
        var attempt = 0
        while (true) {
            val outcome = runCatchingNetwork(method, path) { once(method, path, body, bearer, timeoutMs) }
            val retry = method == "GET" && attempt < getRetries && (outcome.isFailure && (outcome.exceptionOrNull() as? ApiError)?.isRetryable == true ||
                outcome.getOrNull()?.status.let { it == 429 || (it != null && it in 500..599) })
            if (!retry) return outcome.getOrThrow()
            attempt++
            AppLog.w("http", "$method $path retry #$attempt")
            delay(backoffMs * attempt)
        }
    }

    private suspend fun <T> runCatchingNetwork(method: String, path: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError) {
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure((if (isTimeoutError(e)) ApiError(0, Language.text(Msg.Timeout), kind = ErrorKind.Timeout, cause = e) else ApiError(0, Language.text(Msg.NetworkUnreachable), kind = ErrorKind.Network, cause = e)).logFailure(method, path))
    }

    private suspend fun once(method: String, path: String, body: JsonObject?, bearer: String?, timeoutMs: Long?): RawResponse {
        val first = failover.current()
        return try {
            send1(first, method, path, body, bearer, timeoutMs).also { failover.markWorking(first) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val next = failover.next(first)
            if (!(isUnreachableError(e) || (method == "GET" && isTimeoutError(e))) || next == first) throw e
            send1(next, method, path, body, bearer, timeoutMs).also { failover.markWorking(next) }
        }
    }

    private suspend fun send1(base: String, method: String, path: String, body: JsonObject?, bearer: String?, timeoutMs: Long?): RawResponse {
        val started = TimeSource.Monotonic.markNow()
        val response = http.request {
            this.method = HttpMethod.parse(method)
            url(base + path)
            if (timeoutMs != null) timeout { requestTimeoutMillis = timeoutMs }
            header(HttpHeaders.Accept, "application/json")
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        val requestId = response.headers["X-Request-Id"]
        AppLog.d("http", "$method $path -> ${response.status.value} (${started.elapsedNow().inWholeMilliseconds} ms)" + (requestId?.let { " req=$it" } ?: ""))

        return RawResponse(response.status.value, response.bodyAsText(), requestId)
    }
}

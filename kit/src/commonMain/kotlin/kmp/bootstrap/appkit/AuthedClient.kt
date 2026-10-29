package kmp.bootstrap.appkit

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

data class RawResponse(val status: Int, val body: String, val requestId: String?)

/**
 * Lớp HTTP chung của app: gắn Bearer + `X-App-Id`, làm mới access token trước khi hết hạn, và khi bị từ chối thì làm mới
 * rồi GỬI LẠI đúng một lần — người dùng không thấy lỗi.
 *
 * Bất biến quan trọng: refresh token chỉ dùng được MỘT lần, nên nhiều request 401 cùng lúc phải đi qua MỘT lần đổi
 * (khoá + so access token đã dùng). Mỗi request tự đổi sẽ bị server coi là dùng lại và thu hồi cả phiên.
 *
 * Mạng hỏng khi làm mới → giữ token, lần sau thử lại. Refresh chết thật → xoá token + [sessionExpired].
 */
class AuthedClient(
    private val failover: Failover,
    private val tokens: TokenStore,
    private val contract: AuthContract,
    private val appId: String,
    engine: HttpClientEngine? = null,
    /** Làm mới sớm khi access token còn ít hơn chừng này (giây); 0 = chỉ làm mới sau khi bị từ chối. */
    private val refreshMarginSeconds: Long = 120,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    /** Một địa chỉ máy chủ duy nhất (không failover). */
    constructor(
        baseUrl: String, tokens: TokenStore, contract: AuthContract, appId: String, engine: HttpClientEngine? = null,
        refreshMarginSeconds: Long = 120, nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    ) : this(Failover(listOf(baseUrl)), tokens, contract, appId, engine, refreshMarginSeconds, nowEpochSeconds)

    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

    private val http: HttpClient = if (engine != null) HttpClient(engine) { configure() } else HttpClient { configure() }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 15_000 }
        defaultRequest { header("X-App-Id", appId) }
    }

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Refresh token chết thật (hết hạn, bị thu hồi): đưa người dùng về màn đăng nhập. Là sự kiện, không phải trạng thái. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val _sessionRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Một request bị từ chối đã được cứu bằng làm mới: màn nào đang hiện lỗi do token cũ nên tự thử lại. */
    val sessionRefreshed: SharedFlow<Unit> = _sessionRefreshed.asSharedFlow()

    private val refreshLock = Mutex()

    /** Lưu cặp token từ phản hồi đăng nhập/đăng ký. Trả false nếu phản hồi không có token. */
    fun storeLogin(response: RawResponse): Boolean {
        val pair = contract.parseTokens(response.status, response.body) ?: return false
        tokens.save(pair.access, pair.refresh)
        return true
    }

    /** Gửi một request. `auth=false` cho đăng nhập/đăng ký/trang công khai. Không ném lỗi theo mã HTTP — xem [send]. */
    suspend fun raw(method: String, path: String, body: JsonObject? = null, auth: Boolean = true): RawResponse {
        val useAuth = auth && !contract.isAuthPath(path)
        if (useAuth) refreshIfExpiring()
        val used = if (useAuth) tokens.access() else null
        val first = execute(method, path, body, used)
        if (!useAuth || !contract.isTokenRejected(first.status, first.body)) return first
        if (!refreshSession(used)) return first
        val retried = execute(method, path, body, tokens.access())
        if (!contract.isTokenRejected(retried.status, retried.body)) _sessionRefreshed.tryEmit(Unit)

        return retried
    }

    /** Như [raw] nhưng ném [ApiError] khi mã HTTP không phải 2xx, và giải mã phần thân. */
    suspend fun <T> send(method: String, path: String, body: JsonObject? = null, auth: Boolean = true, decode: (String) -> T): T {
        val r = raw(method, path, body, auth)
        if (r.status !in 200..299) throw ApiError(r.status, contract.errorMessage(r.status, r.body), r.requestId)

        return decode(r.body)
    }

    /** Thu hồi phiên trên máy chủ rồi xoá token cục bộ. Lỗi mạng không chặn việc đăng xuất ở máy. */
    suspend fun logout(all: Boolean = false) {
        try {
            raw("POST", contract.logoutPath, contract.logoutBody(tokens.refresh(), all))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // best-effort
        } finally {
            tokens.clear()
        }
    }

    /**
     * Làm mới phiên NGAY, từ bất kỳ đâu (khi mở app, khi app quay lại từ nền, trước một thao tác dài…). An toàn gọi đồng thời:
     * dùng chung khoá với các request đang tự làm mới nên refresh token vẫn chỉ bị đổi một lần.
     * Trả true nếu đang có phiên hợp lệ sau lệnh gọi; false nếu không có phiên, mạng hỏng, hoặc phiên đã chết.
     */
    suspend fun refreshNow(): Boolean {
        if (tokens.refresh() == null) return false
        return refreshSession(tokens.access())
    }

    /** Có cặp token trong máy (chưa chắc còn hợp lệ). */
    fun hasSession(): Boolean = tokens.access() != null && tokens.refresh() != null

    private suspend fun refreshIfExpiring() {
        if (refreshMarginSeconds <= 0 || tokens.refresh() == null) return
        val access = tokens.access() ?: return
        val left = Jwt.secondsLeft(access, nowEpochSeconds()) ?: return
        if (left < refreshMarginSeconds) refreshSession(access)
    }

    private suspend fun refreshSession(usedAccess: String?): Boolean = refreshLock.withLock {
        if (tokens.access() != usedAccess) return@withLock true // request khác vừa đổi xong
        val stored = tokens.refresh() ?: return@withLock false
        val r = try {
            execute("POST", contract.refreshPath, contract.refreshBody(stored), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withLock false // mạng hỏng: giữ token
        }
        val pair = contract.parseTokens(r.status, r.body)
        when {
            pair != null -> { tokens.save(pair.access, pair.refresh); AppLog.i("auth", "đã làm mới phiên"); true }
            contract.isRefreshDead(r.status, r.body) -> { AppLog.w("auth", "refresh token đã chết (HTTP ${r.status}) — cần đăng nhập lại"); tokens.clear(); _sessionExpired.tryEmit(Unit); false }
            else -> false
        }
    }

    private suspend fun execute(method: String, path: String, body: JsonObject?, bearer: String?): RawResponse {
        val first = failover.current()
        return try {
            send1(first, method, path, body, bearer).also { failover.markWorking(first) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val retryable = isUnreachableError(e) || (method == "GET" && isTimeoutError(e))
            val next = failover.next(first)
            if (!retryable || next == first) throw if (e is ApiError) e else ApiError(0, "Không kết nối được máy chủ")
            send1(next, method, path, body, bearer).also { failover.markWorking(next) }
        }
    }

    private suspend fun send1(base: String, method: String, path: String, body: JsonObject?, bearer: String?): RawResponse {
        val started = TimeSource.Monotonic.markNow()
        val response = http.request {
            this.method = HttpMethod.parse(method)
            url(base + path)
            header(HttpHeaders.Accept, "application/json")
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
        }

        val requestId = response.headers["X-Request-Id"]
        AppLog.d("http", "$method $path -> ${response.status.value} (${started.elapsedNow().inWholeMilliseconds} ms)" + (requestId?.let { " req=$it" } ?: ""))

        return RawResponse(response.status.value, response.bodyAsText(), requestId)
    }
}

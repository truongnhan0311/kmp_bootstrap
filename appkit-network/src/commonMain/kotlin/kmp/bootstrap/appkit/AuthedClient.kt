package kmp.bootstrap.appkit

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Lớp đăng nhập bọc [HttpTransport]: gắn Bearer + `X-App-Id`, làm mới access token trước khi hết hạn, và khi bị từ chối thì làm mới
 * rồi GỬI LẠI đúng một lần — người dùng không thấy lỗi.
 *
 * Bất biến quan trọng: refresh token chỉ dùng được MỘT lần, nên nhiều request 401 cùng lúc phải đi qua MỘT lần đổi
 * (khoá + so access token đã dùng). Mỗi request tự đổi sẽ bị server coi là dùng lại và thu hồi cả phiên.
 *
 * Mạng hỏng khi làm mới → giữ token, lần sau thử lại. Refresh chết thật → xoá token + [sessionExpired].
 */
class AuthedClient(
    private val transport: HttpTransport,
    private val tokens: TokenStore,
    private val contract: AuthContract,
    /** Làm mới sớm khi access token còn ít hơn chừng này (giây); 0 = chỉ làm mới sau khi bị từ chối. */
    private val refreshMarginSeconds: Long = 120,
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    /** Dựng kèm [HttpTransport] với danh sách địa chỉ (failover). */
    constructor(
        failover: Failover, tokens: TokenStore, contract: AuthContract, appId: String, engine: HttpClientEngine? = null,
        refreshMarginSeconds: Long = 120, nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    ) : this(HttpTransport(failover, appId, engine), tokens, contract, refreshMarginSeconds, nowEpochSeconds)

    /** Một địa chỉ máy chủ duy nhất (không failover). */
    constructor(
        baseUrl: String, tokens: TokenStore, contract: AuthContract, appId: String, engine: HttpClientEngine? = null,
        refreshMarginSeconds: Long = 120, nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
    ) : this(HttpTransport(baseUrl, appId, engine), tokens, contract, refreshMarginSeconds, nowEpochSeconds)

    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Refresh token chết thật (hết hạn, bị thu hồi): đưa người dùng về màn đăng nhập. Là sự kiện, không phải trạng thái. */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val _sessionRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Một request bị từ chối đã được cứu bằng làm mới: màn nào đang hiện lỗi do token cũ nên tự thử lại. */
    val sessionRefreshed: SharedFlow<Unit> = _sessionRefreshed.asSharedFlow()

    private val refreshLock = Mutex()

    /** Cache phản hồi GET (xem Cache.kt). Mặc định trong bộ nhớ; app đổi sang `ResponseCache(FileCacheStore(platformCacheDir(name)))` để còn sau khi mở lại. */
    var cache: ResponseCache = ResponseCache()

    /** Lưu cặp token từ phản hồi đăng nhập/đăng ký. Trả false nếu phản hồi không có token. */
    fun storeLogin(response: RawResponse): Boolean {
        val pair = contract.parseTokens(response.status, response.body) ?: return false
        tokens.save(pair.access, pair.refresh)
        return true
    }

    /** Gửi một request. `auth=false` cho đăng nhập/đăng ký/trang công khai. Không ném lỗi theo mã HTTP — xem [send]. */
    suspend fun raw(method: String, path: String, body: JsonObject? = null, auth: Boolean = true, timeoutMs: Long? = null, cachePolicy: CachePolicy? = null): RawResponse {
        if (method == "GET" && cachePolicy != null) return cachedGet(path, auth, timeoutMs, cachePolicy)
        val r = network(method, path, body, auth, timeoutMs)
        if (method != "GET" && r.status in 200..299) cache.prefixesToInvalidate(path).forEach { cache.invalidate(it) }

        return r
    }

    private suspend fun cachedGet(path: String, auth: Boolean, timeoutMs: Long?, policy: CachePolicy): RawResponse {
        if (!policy.refresh) cache.lookup(path, policy)?.let { AppLog.d("cache", "GET $path hit"); return RawResponse(it.status, it.body, it.requestId) }
        val fresh = try {
            network("GET", path, null, auth, timeoutMs)
        } catch (e: ApiError) {
            val stale = if (policy.staleIfError && e.isRetryable) cache.lookup(path, policy, allowStale = true) else null
            if (stale == null) throw e
            AppLog.w("cache", "GET $path failed (${e.kind}) - serving stale copy")
            return RawResponse(stale.status, stale.body, stale.requestId)
        }
        if (fresh.status in 200..299) { cache.save(path, fresh); return fresh }
        val stale = if (policy.staleIfError && ErrorKind.ofStatus(fresh.status) == ErrorKind.Server) cache.lookup(path, policy, allowStale = true) else null
        if (stale != null) { AppLog.w("cache", "GET $path -> ${fresh.status} - serving stale copy"); return RawResponse(stale.status, stale.body, stale.requestId) }

        return fresh
    }

    private suspend fun network(method: String, path: String, body: JsonObject?, auth: Boolean, timeoutMs: Long?): RawResponse {
        val useAuth = auth && !contract.isAuthPath(path)
        if (useAuth) refreshIfExpiring()
        val used = if (useAuth) tokens.access() else null
        val first = transport.execute(method, path, body, used, timeoutMs)
        if (!useAuth || !contract.isTokenRejected(first.status, first.body)) return first
        if (!refreshSession(used)) return first
        val retried = transport.execute(method, path, body, tokens.access(), timeoutMs)
        if (!contract.isTokenRejected(retried.status, retried.body)) _sessionRefreshed.tryEmit(Unit)

        return retried
    }

    /** Như [raw] nhưng ném [ApiError] khi mã HTTP không phải 2xx, và giải mã phần thân. */
    suspend fun <T> send(method: String, path: String, body: JsonObject? = null, auth: Boolean = true, decode: (String) -> T): T {
        val r = raw(method, path, body, auth)
        if (r.status !in 200..299) throw ApiError(r.status, errorMessageOf(r), r.requestId).logFailure(method, path)

        return decode(r.body)
    }

    /** Câu báo lỗi đọc được từ phản hồi lỗi, theo hợp đồng của backend. */
    fun errorMessageOf(r: RawResponse): String = contract.errorMessage(r.status, r.body) ?: ErrorKind.ofStatus(r.status).defaultMessage()

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
            cache.clear() // dữ liệu của người vừa đăng xuất không được còn trong máy
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
            transport.execute("POST", contract.refreshPath, contract.refreshBody(stored), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withLock false // mạng hỏng: giữ token
        }
        val pair = contract.parseTokens(r.status, r.body)
        when {
            pair != null -> { tokens.save(pair.access, pair.refresh); AppLog.i("auth", "session refreshed"); true }
            contract.isRefreshDead(r.status, r.body) -> { AppLog.w("auth", "refresh token is dead (HTTP ${r.status}) - sign-in required"); tokens.clear(); cache.clear(); _sessionExpired.tryEmit(Unit); false }
            else -> false
        }
    }
}

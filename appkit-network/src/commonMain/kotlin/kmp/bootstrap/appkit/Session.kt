package kmp.bootstrap.appkit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

sealed interface SessionState<out U> {
    /** Đang kiểm tra phiên lúc mở app (hiện màn chờ). */
    data object Loading : SessionState<Nothing>
    data object SignedOut : SessionState<Nothing>
    data class SignedIn<U>(val user: U) : SessionState<U>
}

/*
 * Phiên người dùng cho UI: "đang kiểm tra / chưa đăng nhập / đã đăng nhập (kèm thông tin người dùng)". Bọc [AuthedClient] nên mọi việc
 * token đã được lo; lớp này chỉ giữ TRẠNG THÁI để màn hình quan sát:
 *
 *     val session = Session(client, loadUser = { client.get<User>("/api/me") }, userSerializer = User.serializer(), prefs = storage.prefs)
 *     session.restore()                                   // lúc mở app
 *     val state by session.state.collectAsState()         // Loading → hiện màn chờ; SignedOut → màn đăng nhập; SignedIn(user) → app
 *     session.signIn(client.raw("POST", endpoints.login, body, auth = false))
 *     session.signOut { push.unregister() }               // chạy việc dọn TRƯỚC khi quên token (gỡ thông báo đẩy…)
 *
 * Hết phiên thật (refresh token chết) → tự về SignedOut. Mở app khi mất mạng mà đã có phiên → vào app bằng thông tin người dùng lưu lần trước
 * (nếu truyền `userSerializer` + `prefs`), không bị đá ra màn đăng nhập oan.
 */
class Session<U : Any>(
    private val client: AuthedClient,
    private val loadUser: suspend () -> U,
    private val userSerializer: KSerializer<U>? = null,
    private val prefs: KeyValueStore? = null,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _state = MutableStateFlow<SessionState<U>>(SessionState.Loading)
    val state: StateFlow<SessionState<U>> = _state.asStateFlow()
    val user: U? get() = (_state.value as? SessionState.SignedIn<U>)?.user

    /** Chạy sau mỗi lần có người dùng (đăng nhập, mở lại app): đồng bộ cài đặt, đăng ký thiết bị nhận thông báo… */
    var onSignedIn: suspend (U) -> Unit = {}

    init { scope.launch { client.sessionExpired.collect { forget(); _state.value = SessionState.SignedOut } } }

    /** Gọi lúc mở app. */
    suspend fun restore() {
        if (!client.hasSession()) { _state.value = SessionState.SignedOut; return }
        try {
            accept(loadUser())
        } catch (e: ApiError) {
            when {
                e.isUnauthorized -> { client.logoutLocal(); forget(); _state.value = SessionState.SignedOut }
                e.isNetwork -> _state.value = readCached()?.let { SessionState.SignedIn(it) } ?: SessionState.SignedOut   // offline: dùng người dùng đã lưu
                else -> throw e
            }
        }
    }

    /** Sau lời gọi đăng nhập/đăng ký: lưu token rồi tải người dùng. Trả false nếu phản hồi không có token (sai mật khẩu…); khi đó gọi `client.errorMessageOf(response)`. */
    suspend fun signIn(response: RawResponse): Boolean {
        if (!client.storeLogin(response)) return false
        accept(loadUser())
        return true
    }

    /** Tải lại thông tin người dùng (sau khi sửa hồ sơ…). */
    suspend fun refreshUser() { if (_state.value is SessionState.SignedIn<*>) accept(loadUser()) }

    /** Đăng xuất: chạy [beforeLogout] (còn token để gọi API), thu hồi phiên trên máy chủ, xoá token + cache + người dùng đã lưu. */
    suspend fun signOut(all: Boolean = false, beforeLogout: suspend () -> Unit = {}) {
        try { beforeLogout() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { AppLog.w("session", "pre-logout cleanup failed", e) }
        client.logout(all); forget(); _state.value = SessionState.SignedOut
    }

    private suspend fun accept(user: U) {
        remember(user); _state.value = SessionState.SignedIn(user)
        try { onSignedIn(user) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { AppLog.w("session", "onSignedIn hook failed", e) }
    }

    private fun remember(user: U) { val ser = userSerializer ?: return; prefs?.put(KEY, Json.encodeToString(ser, user)) }
    private fun readCached(): U? {
        val ser = userSerializer ?: return null
        val raw = prefs?.get(KEY) ?: return null
        return runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(ser, raw) }.getOrNull()
    }
    private fun forget() { prefs?.put(KEY, null) }

    private companion object { const val KEY = "session.user" }
}

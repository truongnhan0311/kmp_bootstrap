package kmp.bootstrap.appkit.platform

import kmp.bootstrap.appkit.KeyValueStore
import kmp.bootstrap.appkit.Language
import kmp.bootstrap.appkit.Msg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/*
 * Khoá app bằng khuôn mặt / vân tay / mật mã máy, theo kiểu app ngân hàng:
 *
 *     val lock = AppLock(deviceAuth(), AppKit.storage.prefs)
 *     when (lock.state.value) { Off, Unlocked -> app; Locked -> màn "Mở khoá"; NeedsPassword -> màn đăng nhập }
 *     lock.unlock()                 // nút "Mở khoá"
 *     lock.onBackground() / lock.onForeground()   // từ vòng đời app: khoá lại sau [timeout] ở nền
 *
 * Mở khoá diễn ra TRÊN MÁY: không gọi máy chủ, phiên (token) đã lưu vẫn là phiên cũ. Đây là cửa của giao diện; token vẫn nằm
 * trong Keystore/Keychain như trước. Máy mất hết khoá màn hình → NeedsPassword: đăng nhập lại bằng mật khẩu rồi gọi [passwordLoginSucceeded].
 */
class AppLock(
    private val auth: DeviceAuth,
    private val prefs: KeyValueStore,
    private val timeout: Duration = 5.minutes,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    enum class State {
        /** Người dùng chưa bật khoá. */
        Off,
        /** Phải mở khoá trước khi thấy nội dung. */
        Locked,
        Unlocked,
        /** Đã bật khoá nhưng máy không còn khoá màn hình: dùng mật khẩu tài khoản. */
        NeedsPassword,
    }

    private val _state = MutableStateFlow(startState())
    val state: StateFlow<State> = _state.asStateFlow()
    private var backgroundAt: Long? = null

    val enabled: Boolean get() = prefs.get(KEY) == "1"

    private fun startState(): State = when {
        !enabled -> State.Off
        auth.available() == DeviceAuthKind.None -> State.NeedsPassword
        else -> State.Locked
    }

    /** Bật khoá: phải xác thực thành công một lần ngay lúc bật (để chắc người bật là chủ máy và máy có khoá). */
    suspend fun enable(reason: String = Language.text(Msg.UnlockPrompt)): DeviceAuthResult {
        if (auth.available() == DeviceAuthKind.None) return DeviceAuthResult.NotAvailable
        val r = auth.authenticate(reason)
        if (r == DeviceAuthResult.Success) {
            prefs.put(KEY, "1")
            _state.value = State.Unlocked
        }
        return r
    }

    fun disable() {
        prefs.put(KEY, null)
        backgroundAt = null
        _state.value = State.Off
    }

    /** Mở khoá. Không bật khoá thì coi như đã mở. */
    suspend fun unlock(reason: String = Language.text(Msg.UnlockPrompt)): DeviceAuthResult {
        if (!enabled) { _state.value = State.Off; return DeviceAuthResult.Success }
        if (auth.available() == DeviceAuthKind.None) { _state.value = State.NeedsPassword; return DeviceAuthResult.NotAvailable }
        val r = auth.authenticate(reason)
        when (r) {
            DeviceAuthResult.Success -> _state.value = State.Unlocked
            DeviceAuthResult.LockedOut, DeviceAuthResult.NotAvailable -> _state.value = State.NeedsPassword
            else -> Unit   // huỷ hay lỗi: vẫn khoá, người dùng thử lại
        }
        return r
    }

    fun onBackground() {
        if (_state.value == State.Unlocked) backgroundAt = nowMillis()
    }

    fun onForeground() {
        val since = backgroundAt ?: return
        backgroundAt = null
        if (enabled && _state.value == State.Unlocked && nowMillis() - since >= timeout.inWholeMilliseconds) {
            _state.value = if (auth.available() == DeviceAuthKind.None) State.NeedsPassword else State.Locked
        }
    }

    /** Người dùng đã đăng nhập bằng mật khẩu (đường dự phòng): mở cho phiên này. */
    fun passwordLoginSucceeded() {
        _state.value = if (enabled) State.Unlocked else State.Off
    }

    /** Đăng xuất: tắt khoá, người dùng sau tự bật lại cho tài khoản của mình. */
    fun signedOut() = disable()

    private companion object { const val KEY = "appkit.applock.enabled" }
}

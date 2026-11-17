package kmp.bootstrap.appkit.platform

import kmp.bootstrap.appkit.KeyValueStore
import kmp.bootstrap.appkit.Language
import kmp.bootstrap.appkit.Msg
import kotlinx.coroutines.CancellationException

/*
 * Đăng nhập bằng khuôn mặt / vân tay / mật mã máy, có máy chủ xác nhận (khác AppLock chỉ mở khoá trên máy):
 *
 *     val login = DeviceKeyLogin(deviceKeyStore(), backend, AppKit.storage.prefs, deviceId = { installId })
 *     login.enable(userId, "iPhone của Minh", "ios")   // sau khi đăng nhập mật khẩu, ở màn Tài khoản
 *     login.lastUser                                    // màn đăng nhập: có thì hiện nút "Đăng nhập bằng Face ID"
 *     when (val r = login.login(userId)) { is Success -> lưu r.session; Disabled -> về mật khẩu; ... }
 *     login.disable(userId)                             // tắt; server thu hồi khoá của máy này
 *
 * Mỗi tài khoản một khoá riêng trên máy (nhiều tài khoản trên một máy → nhiều khoá).
 * Máy chủ trả "khoá chưa đăng ký" (đã thu hồi) → backend ném [DeviceKeyNotRegistered]: kit xoá khoá trên máy, trả [Outcome.Disabled].
 */

/** Phía máy chủ; app cài bằng client HTTP của mình. [S] là phiên (cặp token) máy chủ trả khi đăng nhập. */
interface DeviceKeyBackend<S> {
    /** Đang đăng nhập: lưu khoá công khai cho (tài khoản, [deviceId]). Gửi lại thì thay khoá cũ. */
    suspend fun register(deviceId: String, publicKey: String, name: String, platform: String)
    /** Không cần đăng nhập: xin challenge (ngắn hạn, dùng một lần). */
    suspend fun challenge(userId: String, deviceId: String): String
    /** Đổi challenge + chữ ký lấy phiên. */
    suspend fun login(challenge: String, signature: String): S
    /** Đang đăng nhập: thu hồi khoá của [deviceId]. */
    suspend fun unregister(deviceId: String)
}

/** Máy chủ nói máy này chưa bật hoặc đã bị thu hồi (Recam: `401 device_key_not_registered`). */
class DeviceKeyNotRegistered(message: String = "device key not registered") : Exception(message)

class DeviceKeyLogin<S>(
    private val store: DeviceKeyStore?,
    private val backend: DeviceKeyBackend<S>,
    private val prefs: KeyValueStore,
    private val deviceId: () -> String,
    /** Cho dùng mật mã máy khi không có / không muốn dùng sinh trắc học. iOS `.userPresence`; Android 11+ `DEVICE_CREDENTIAL`. */
    val allowPasscode: Boolean = true,
    private val prefix: String = "appkit.devicekey.",
) {
    sealed interface Outcome<out S> {
        data class Success<S>(val session: S) : Outcome<S>
        /** Người dùng bấm huỷ: ở lại màn đăng nhập. */
        data object Cancelled : Outcome<Nothing>
        /** Không còn bật cho tài khoản này (máy chủ thu hồi, đổi khuôn mặt / vân tay): đăng nhập mật khẩu rồi bật lại. */
        data object Disabled : Outcome<Nothing>
        /** Máy không có cách xác thực, hoặc chưa bật trên máy này. */
        data object Unavailable : Outcome<Nothing>
        /** Sai quá nhiều lần: dùng mật khẩu. */
        data object LockedOut : Outcome<Nothing>
        /** Lỗi mạng / máy chủ; [cause] để ghi log, thử lại được. */
        data class Error(val cause: Throwable?) : Outcome<Nothing>
    }

    fun alias(userId: String) = "$prefix$userId"

    /** Máy có cách xác thực phù hợp (để hiện công tắc "Đăng nhập bằng Face ID"). */
    fun available(): Boolean = runCatching { store?.isAvailable(allowPasscode) == true }.getOrDefault(false)

    /** Đã bật cho [userId] trên máy này. */
    fun enabledFor(userId: String): Boolean =
        prefs.get(flag(userId)) == "1" && runCatching { store?.hasKey(alias(userId)) == true }.getOrDefault(false)

    /** Tài khoản bật gần nhất trên máy này (màn đăng nhập dùng để hiện nút). null nếu không còn bật. */
    val lastUser: String? get() = prefs.get(lastKey)?.takeIf { enabledFor(it) }

    /** Bật cho [userId] (đang đăng nhập). Lỗi máy chủ → ném lại, khoá trên máy được xoá. false nếu máy không hỗ trợ. */
    suspend fun enable(userId: String, name: String, platform: String): Boolean {
        val s = store ?: return false
        if (!available()) return false
        val a = alias(userId)
        val pub = s.createKey(a, allowPasscode) ?: return false
        try {
            backend.register(deviceId(), pub, name, platform)
        } catch (e: Throwable) {
            s.deleteKey(a)
            throw e
        }
        prefs.put(flag(userId), "1")
        prefs.put(lastKey, userId)
        return true
    }

    /** Đăng nhập [userId] bằng lời nhắc của hệ điều hành. */
    suspend fun login(userId: String, reason: String = Language.text(Msg.UnlockPrompt)): Outcome<S> {
        val s = store ?: return Outcome.Unavailable
        if (!enabledFor(userId)) return Outcome.Unavailable
        val a = alias(userId)
        val challenge = try {
            backend.challenge(userId, deviceId())
        } catch (e: DeviceKeyNotRegistered) {
            forget(userId); return Outcome.Disabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome.Error(e)
        }
        val signature = when (val r = s.signAwait(a, challenge, reason, allowPasscode)) {
            is DeviceKeySignResult.Success -> r.signature
            DeviceKeySignResult.Cancelled -> return Outcome.Cancelled
            DeviceKeySignResult.LockedOut -> return Outcome.LockedOut
            DeviceKeySignResult.Invalidated -> { forget(userId); return Outcome.Disabled }
            DeviceKeySignResult.Failed -> return Outcome.Error(null)
        }
        return try {
            Outcome.Success(backend.login(challenge, signature))
        } catch (e: DeviceKeyNotRegistered) {
            forget(userId); Outcome.Disabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Outcome.Error(e)
        }
    }

    /** Tắt cho [userId]: xoá khoá trên máy, rồi báo máy chủ nếu [signedIn] (lỗi mạng bỏ qua: khoá đã mất, không ký được nữa). */
    suspend fun disable(userId: String, signedIn: Boolean = true) {
        forget(userId)
        if (!signedIn) return
        try {
            backend.unregister(deviceId())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /** Chỉ xoá phía máy (vd máy chủ đã thu hồi). */
    fun forget(userId: String) {
        runCatching { store?.deleteKey(alias(userId)) }
        prefs.put(flag(userId), null)
        if (prefs.get(lastKey) == userId) prefs.put(lastKey, null)
    }

    private fun flag(userId: String) = "${prefix}enabled.$userId"
    private val lastKey get() = "${prefix}last_user"
}

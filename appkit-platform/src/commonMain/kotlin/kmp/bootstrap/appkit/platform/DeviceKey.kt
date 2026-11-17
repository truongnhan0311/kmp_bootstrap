package kmp.bootstrap.appkit.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/*
 * Khoá đăng nhập nằm trong chip bảo mật của máy (Android Keystore, iOS Secure Enclave), ECDSA P-256, kiểu passkey.
 * Khoá riêng KHÔNG rời máy và chỉ ký sau khi người dùng qua lời nhắc của hệ điều hành (khuôn mặt / vân tay, và mật mã máy
 * nếu [allowPasscode]). Server chỉ giữ khoá công khai. Đổi khuôn mặt / vân tay trong cài đặt máy thì khoá chỉ-sinh-trắc-học
 * tự mất hiệu lực ([DeviceKeyStore.ERROR_INVALIDATED]): app tắt tính năng, người dùng bật lại sau khi đăng nhập mật khẩu.
 *
 * Định dạng dùng chung với server (vector khoá ở cả hai bên):
 *   khoá công khai = base64 của SPKI DER (Android getEncoded) hoặc điểm 65 byte 0x04‖X‖Y (iOS SecKeyCopyExternalRepresentation)
 *   chữ ký         = base64 của ECDSA SHA-256 dạng ASN.1 DER trên chuỗi [message] mã UTF-8
 *
 * Giao diện dùng callback (không phải suspend) để Swift cài được trên iOS; Kotlin gọi [signAwait].
 */
interface DeviceKeyStore {
    /** Máy có cách xác thực phù hợp: sinh trắc học mạnh, hoặc (nếu [allowPasscode]) cả mật mã máy. */
    fun isAvailable(allowPasscode: Boolean): Boolean

    fun hasKey(alias: String): Boolean

    /** Tạo (thay) cặp khoá [alias]; trả khoá công khai base64, null nếu lỗi. Không hiện lời nhắc. */
    fun createKey(alias: String, allowPasscode: Boolean): String?

    /**
     * Hiện lời nhắc của hệ điều hành ([reason]) rồi ký [message]. [callback] được gọi đúng một lần, trên main thread:
     * (chữ ký base64, null) hoặc (null, [ERROR_CANCELLED] / [ERROR_INVALIDATED] / [ERROR_LOCKED_OUT] / [ERROR_FAILED]).
     */
    fun sign(alias: String, message: String, reason: String, allowPasscode: Boolean, callback: (signature: String?, error: String?) -> Unit)

    fun deleteKey(alias: String)

    companion object {
        const val ERROR_CANCELLED = "cancelled"
        const val ERROR_INVALIDATED = "invalidated"
        const val ERROR_LOCKED_OUT = "locked_out"
        const val ERROR_FAILED = "failed"
    }
}

/** Kho của nền tảng đang chạy; null khi không có (desktop, hoặc iOS chưa gán `IosDeviceKeys.store` từ Swift). */
expect fun deviceKeyStore(): DeviceKeyStore?

sealed interface DeviceKeySignResult {
    data class Success(val signature: String) : DeviceKeySignResult
    data object Cancelled : DeviceKeySignResult
    /** Khoá không còn dùng được (đổi khuôn mặt / vân tay, bị xoá): phải bật lại. */
    data object Invalidated : DeviceKeySignResult
    /** Sai quá nhiều lần, hệ điều hành tạm khoá. */
    data object LockedOut : DeviceKeySignResult
    data object Failed : DeviceKeySignResult
}

/** [DeviceKeyStore.sign] dạng suspend. */
suspend fun DeviceKeyStore.signAwait(alias: String, message: String, reason: String, allowPasscode: Boolean): DeviceKeySignResult =
    suspendCancellableCoroutine { cont ->
        sign(alias, message, reason, allowPasscode) { signature, error ->
            if (!cont.isActive) return@sign
            cont.resume(
                when {
                    signature != null -> DeviceKeySignResult.Success(signature)
                    error == DeviceKeyStore.ERROR_CANCELLED -> DeviceKeySignResult.Cancelled
                    error == DeviceKeyStore.ERROR_INVALIDATED -> DeviceKeySignResult.Invalidated
                    error == DeviceKeyStore.ERROR_LOCKED_OUT -> DeviceKeySignResult.LockedOut
                    else -> DeviceKeySignResult.Failed
                },
            )
        }
    }

/**
 * Bản giả cho test và xem thử màn hình: không có mật mã thật; "chữ ký" = `fake-sig:<alias>:<message>`.
 * Đặt [next] để giả lời nhắc bị huỷ / khoá mất hiệu lực…
 */
class FakeDeviceKeyStore(var availableBiometric: Boolean = true, var availablePasscode: Boolean = true) : DeviceKeyStore {
    val keys = mutableMapOf<String, String>()
    val prompts = mutableListOf<String>()
    /** null = ký thành công; hoặc một mã ERROR_*. */
    var next: String? = null
    private var counter = 0

    override fun isAvailable(allowPasscode: Boolean) = availableBiometric || (allowPasscode && availablePasscode)
    override fun hasKey(alias: String) = alias in keys
    override fun createKey(alias: String, allowPasscode: Boolean): String? {
        if (!isAvailable(allowPasscode)) return null
        return "fake-pub-${++counter}".also { keys[alias] = it }
    }

    override fun sign(alias: String, message: String, reason: String, allowPasscode: Boolean, callback: (String?, String?) -> Unit) {
        prompts += reason
        val error = next ?: if (alias !in keys) DeviceKeyStore.ERROR_INVALIDATED else null
        if (error != null) callback(null, error) else callback("fake-sig:$alias:$message", null)
    }

    override fun deleteKey(alias: String) { keys.remove(alias) }
}

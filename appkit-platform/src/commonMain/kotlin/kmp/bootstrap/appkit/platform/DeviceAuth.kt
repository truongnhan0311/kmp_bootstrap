package kmp.bootstrap.appkit.platform

/*
 * Xác thực người dùng bằng khoá của MÁY: khuôn mặt / vân tay, không có thì mật mã máy (passcode, PIN, hình vẽ).
 * Hộp thoại là của hệ điều hành; app không bao giờ thấy dữ liệu khuôn mặt, vân tay hay mật mã.
 *   Android: androidx.biometric BiometricPrompt (BIOMETRIC_WEAK | DEVICE_CREDENTIAL) — MainActivity phải là FragmentActivity.
 *   iOS:     LocalAuthentication, chính sách deviceOwnerAuthentication — Info.plist cần NSFaceIDUsageDescription.
 *   Desktop: không có (NotAvailable).
 * Dùng qua AppLock (khoá app); gọi thẳng khi cần xác nhận một thao tác nhạy cảm.
 */
enum class DeviceAuthKind {
    /** Máy không có khoá màn hình nào: không xác thực được, phải dùng mật khẩu tài khoản. */
    None,
    /** Chỉ có mật mã máy. */
    Passcode,
    /** Có khuôn mặt hoặc vân tay đã đăng ký (mật mã máy vẫn là đường dự phòng). */
    Biometric,
}

sealed interface DeviceAuthResult {
    data object Success : DeviceAuthResult
    /** Người dùng bấm huỷ, hoặc hệ thống huỷ (app chuyển nền, cuộc gọi đến…). */
    data object Cancelled : DeviceAuthResult
    /** Máy không có khoá màn hình, hoặc người dùng vừa gỡ. */
    data object NotAvailable : DeviceAuthResult
    /** Sai quá nhiều lần, hệ điều hành tạm khoá. App nên đưa về đăng nhập bằng mật khẩu. */
    data object LockedOut : DeviceAuthResult
    /** Lỗi khác; [reason] tiếng Anh, cho log. */
    data class Failed(val reason: String) : DeviceAuthResult
}

interface DeviceAuth {
    fun available(): DeviceAuthKind
    /** Hiện hộp thoại của hệ điều hành với lời nhắc [reason] (chữ cho người dùng). */
    suspend fun authenticate(reason: String): DeviceAuthResult
}

/** Bản theo nền tảng đang chạy. */
expect fun deviceAuth(): DeviceAuth

/** Bản giả cho test và xem thử màn hình: trả [next], ghi lại mọi lời nhắc. */
class FakeDeviceAuth(var kind: DeviceAuthKind = DeviceAuthKind.Biometric, var next: DeviceAuthResult = DeviceAuthResult.Success) : DeviceAuth {
    val prompts = mutableListOf<String>()
    override fun available() = kind
    override suspend fun authenticate(reason: String): DeviceAuthResult {
        prompts += reason
        return if (kind == DeviceAuthKind.None) DeviceAuthResult.NotAvailable else next
    }
}

package kmp.bootstrap.appkit.platform

/**
 * Secure Enclave + Face ID / Touch ID / passcode viết bằng Swift (Security, LocalAuthentication dùng từ Swift gọn và an toàn
 * hơn cinterop CFDictionary). App gán một lần lúc khởi động, trước khi dùng DeviceKeyLogin:
 *
 *     IosDeviceKeys.shared.store = SecureEnclaveDeviceKeyStore()
 *
 * Mẫu `SecureEnclaveDeviceKeyStore.swift` ở docs/platform.md, mục "Đăng nhập bằng khuôn mặt / vân tay / mật mã máy".
 */
object IosDeviceKeys {
    @kotlin.concurrent.Volatile
    var store: DeviceKeyStore? = null
}

actual fun deviceKeyStore(): DeviceKeyStore? = IosDeviceKeys.store

package kmp.bootstrap.appkit.platform

/** Desktop không có chip bảo mật dùng chung: không hỗ trợ đăng nhập bằng khoá trong máy (dùng mật khẩu). */
actual fun deviceKeyStore(): DeviceKeyStore? = null

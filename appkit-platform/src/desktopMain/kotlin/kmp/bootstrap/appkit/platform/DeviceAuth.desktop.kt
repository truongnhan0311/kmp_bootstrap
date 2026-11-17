package kmp.bootstrap.appkit.platform

/** Desktop không có khoá sinh trắc học chung: luôn NotAvailable (AppLock sẽ đưa về mật khẩu). */
actual fun deviceAuth(): DeviceAuth = object : DeviceAuth {
    override fun available() = DeviceAuthKind.None
    override suspend fun authenticate(reason: String) = DeviceAuthResult.NotAvailable
}

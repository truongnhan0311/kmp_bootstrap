package kmp.bootstrap.appkit

/** Lỗi mang mã HTTP; `status == 0` là lỗi mạng (chưa tới được máy chủ). */
class ApiError(
    val status: Int,
    override val message: String,
    val requestId: String? = null,
) : Exception(message) {
    val isNetwork: Boolean get() = status == 0
    val isUnauthorized: Boolean get() = status == 401
    val isNotFound: Boolean get() = status == 404
}

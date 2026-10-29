package kmp.bootstrap.appkit

/** Phân loại lỗi để UI quyết định hiện gì mà không phải so mã HTTP khắp nơi. */
enum class ErrorKind {
    /** Chưa tới được máy chủ (mất mạng, DNS, SSL). */ Network,
    /** Quá thời gian chờ. */ Timeout,
    /** 401 — phiên không hợp lệ (đã thử làm mới mà vẫn hỏng). */ Unauthorized,
    /** 403 — đăng nhập rồi nhưng không có quyền. */ Forbidden,
    /** 404 — không có (thường là trạng thái bình thường, không phải sự cố). */ NotFound,
    /** 409 — xung đột trạng thái. */ Conflict,
    /** 400/422 — dữ liệu gửi lên bị từ chối. */ Invalid,
    /** 429 — gọi quá nhiều. */ RateLimited,
    /** 5xx — máy chủ lỗi. */ Server,
    /** Máy chủ trả lời được nhưng không đọc được JSON như mong đợi. */ Parse,
    /** Còn lại. */ Other;

    companion object {
        fun ofStatus(status: Int): ErrorKind = when (status) {
            401 -> Unauthorized; 403 -> Forbidden; 404 -> NotFound; 409 -> Conflict; 400, 422 -> Invalid; 429 -> RateLimited
            in 500..599 -> Server; 0 -> Network; else -> Other
        }
    }
}

/** Lỗi mang mã HTTP (0 = chưa có phản hồi) và [kind]; `requestId` dùng để tra log trên máy chủ. */
class ApiError(
    val status: Int,
    override val message: String,
    val requestId: String? = null,
    val kind: ErrorKind = ErrorKind.ofStatus(status),
    cause: Throwable? = null,
) : Exception(message, cause) {
    val isNetwork get() = kind == ErrorKind.Network || kind == ErrorKind.Timeout
    val isTimeout get() = kind == ErrorKind.Timeout
    val isUnauthorized get() = kind == ErrorKind.Unauthorized
    val isNotFound get() = kind == ErrorKind.NotFound
    /** Đáng thử lại tự động (lỗi tạm): mạng, timeout, 429, 5xx. */
    val isRetryable get() = kind == ErrorKind.Network || kind == ErrorKind.Timeout || kind == ErrorKind.RateLimited || kind == ErrorKind.Server
}

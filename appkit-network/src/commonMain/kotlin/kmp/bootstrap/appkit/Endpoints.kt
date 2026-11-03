package kmp.bootstrap.appkit

/**
 * MỘT chỗ duy nhất khai báo đường dẫn API của app. Không class nào trong kit (contract đăng nhập, contract push…) tự mang
 * đường dẫn bên trong — chúng nhận [Endpoints] này. Đổi phiên bản API (`/api/v2` → `/api/v3`) hoặc đổi backend là sửa ở đây:
 *
 *     val endpoints = Endpoints.taper(prefix = "/api/v2")       // hoặc Endpoints.flat(), hoặc tự khai báo từng đường
 *     AppKit.start(AppKitConfig(name = "my-app", baseUrls = …, contract = PlainJsonContract(endpoints)))
 *     AppKit.endpoints.login                                    // code app cũng lấy đường dẫn từ đây, không gõ chuỗi
 *
 * Đường dẫn riêng của app (hoạt động, hồ sơ…) khai trong [extra] rồi gọi `endpoints["activities"]`.
 */
data class Endpoints(
    val login: String,
    val refresh: String,
    val logout: String,
    val register: String? = null,
    val me: String? = null,
    /** Gốc API đăng ký thiết bị nhận thông báo đẩy (xem PushContract). Null nếu app không dùng push. */
    val devices: String? = null,
    val extra: Map<String, String> = emptyMap(),
) {
    /** Đường dẫn riêng của app khai trong [extra]; thiếu thì báo lỗi rõ ràng thay vì gọi nhầm. */
    operator fun get(name: String): String = extra[name] ?: error("Endpoint '$name' is not defined in Endpoints.extra")

    /**
     * Các đường KHÔNG tự gắn token và KHÔNG tự làm mới: đăng nhập, đăng ký, làm mới (tránh vòng lặp, và các đường này không có phiên để gắn).
     * Đăng xuất KHÔNG nằm ở đây: thu hồi phiên là việc của người đang đăng nhập nên server thường đặt nó sau middleware xác thực
     * (Taper: để đưa access token hiện tại vào danh sách chặn) — thiếu Bearer thì đăng xuất âm thầm thất bại, refresh token vẫn sống.
     */
    internal val authPaths: Set<String> get() = setOfNotNull(login, register, refresh)

    fun requireDevices(): String = devices ?: error("Endpoints.devices is required for push notifications")

    companion object {
        /** Taper (Goravel): `{prefix}/auth/login|register|refresh|logout|me`, thiết bị ở `{prefix}/me/devices`. */
        fun taper(prefix: String = "/api/v2", extra: Map<String, String> = emptyMap()) = Endpoints(
            login = "$prefix/auth/login", register = "$prefix/auth/register", refresh = "$prefix/auth/refresh",
            logout = "$prefix/auth/logout", me = "$prefix/auth/me", devices = "$prefix/me/devices", extra = extra,
        )

        /**
         * Kiểu phẳng: `{prefix}/login`, `{prefix}/refresh`, `{prefix}/logout`; push đăng ký ở [pushPath]. Backend có đường khác (ví dụ `/refresh/token`)
         * thì tự khai `Endpoints(login = …, refresh = …, logout = …)` ngay trong app — đường dẫn riêng của một backend không thuộc về kit.
         */
        fun flat(prefix: String = "/api", pushPath: String = "/push", extra: Map<String, String> = emptyMap()) = Endpoints(
            login = "$prefix/login", refresh = "$prefix/refresh", logout = "$prefix/logout", devices = pushPath, extra = extra,
        )
    }
}

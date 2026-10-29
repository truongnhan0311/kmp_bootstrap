package kmp.bootstrap.appkit

/**
 * MỘT chỗ duy nhất khai báo đường dẫn API của app. Không class nào trong kit (contract đăng nhập, contract push…) tự mang
 * đường dẫn bên trong — chúng nhận [Endpoints] này. Đổi phiên bản API (`/api/v2` → `/api/v3`) hoặc đổi backend là sửa ở đây:
 *
 *     val endpoints = Endpoints.taper(prefix = "/api/v2")       // hoặc Endpoints.qtDepot(), hoặc tự khai báo từng đường
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

    /** Các đường đăng nhập/làm mới/đăng xuất — KHÔNG tự gắn token hay tự làm mới (tránh vòng lặp). */
    internal val authPaths: Set<String> get() = setOfNotNull(login, register, refresh, logout)

    internal fun requireDevices(): String = devices ?: error("Endpoints.devices is required for push notifications")

    companion object {
        /** Taper (Goravel): `{prefix}/auth/login|register|refresh|logout|me`, thiết bị ở `{prefix}/me/devices`. */
        fun taper(prefix: String = "/api/v2", extra: Map<String, String> = emptyMap()) = Endpoints(
            login = "$prefix/auth/login", register = "$prefix/auth/register", refresh = "$prefix/auth/refresh",
            logout = "$prefix/auth/logout", me = "$prefix/auth/me", devices = "$prefix/me/devices", extra = extra,
        )

        /** qt-depot (isto_account_be): `{prefix}/login`, `{prefix}/refresh/token`, `{prefix}/logout`; push đăng ký ở `/push`. */
        fun qtDepot(prefix: String = "/api/v5", pushPath: String = "/push", extra: Map<String, String> = emptyMap()) = Endpoints(
            login = "$prefix/login", refresh = "$prefix/refresh/token", logout = "$prefix/logout", devices = pushPath, extra = extra,
        )
    }
}

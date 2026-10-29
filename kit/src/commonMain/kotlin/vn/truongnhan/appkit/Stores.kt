package vn.truongnhan.appkit

/**
 * Nơi giữ cặp token của phiên. Cài thật theo nền tảng ở app (Android: EncryptedSharedPreferences, iOS: Keychain);
 * test/desktop dùng [MemoryTokenStore]. Kit không bao giờ tự ghi token ra đâu khác.
 */
interface TokenStore {
    fun access(): String?
    fun refresh(): String?
    /** Ghi cả cặp một lúc. `refresh = null` giữ refresh cũ (server không xoay vòng). */
    fun save(access: String, refresh: String?)
    fun clear()
}

class MemoryTokenStore(private var access: String? = null, private var refresh: String? = null) : TokenStore {
    override fun access() = access
    override fun refresh() = refresh
    override fun save(access: String, refresh: String?) { this.access = access; if (refresh != null) this.refresh = refresh }
    override fun clear() { access = null; refresh = null }
}

/** Cặp khoá–giá trị nhỏ cho lựa chọn cục bộ (giao diện, địa chỉ máy chủ). Không dùng cho bí mật. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

package kmp.bootstrap.appkit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/*
 * Lưu trữ cục bộ, MỘT cách gọi cho mọi nền tảng:
 *
 *     val storage = AppStorage("my-app")          // Android: gọi AppKitAndroid.init(context) một lần trước đó
 *     storage.prefs.putBool("dark", true)          // lựa chọn thường (giao diện, ngôn ngữ, địa chỉ máy chủ)
 *     storage.tokens                               // TokenStore cho AuthedClient — nằm ở nơi mã hoá của máy
 *
 * `prefs` KHÔNG dành cho bí mật. Token và mọi thứ nhạy cảm chỉ đi qua `secrets` (Android: EncryptedSharedPreferences,
 * iOS: Keychain, desktop: file chỉ chủ sở hữu đọc được — chưa mã hoá, dùng để chạy thử).
 */

/** Nơi lưu thật theo nền tảng: lựa chọn thường (SharedPreferences / NSUserDefaults / file). */
expect fun platformPrefs(name: String): KeyValueStore

/** Nơi lưu bí mật theo nền tảng (Keystore / Keychain / file 0600). Cùng giao diện, khác độ bảo vệ. */
expect fun platformSecrets(name: String): KeyValueStore

/** Cửa vào duy nhất cho lưu trữ của một app. `name` phân tách dữ liệu giữa các app dùng chung kit. */
class AppStorage(name: String) {
    val prefs: KeyValueStore = platformPrefs(name)
    val secrets: KeyValueStore = platformSecrets(name)
    /** Cặp token của phiên, cất trong `secrets`. Đưa thẳng vào `AuthedClient`. */
    val tokens: TokenStore = StoreTokenStore(secrets)
}

/** TokenStore đặt trên một KeyValueStore bất kỳ — không biết nền tảng. */
class StoreTokenStore(private val store: KeyValueStore, private val prefix: String = "auth.") : TokenStore {
    override fun access() = store.get(prefix + "access")
    override fun refresh() = store.get(prefix + "refresh")
    override fun save(access: String, refresh: String?) {
        store.put(prefix + "access", access)
        if (refresh != null) store.put(prefix + "refresh", refresh)
    }
    override fun clear() { store.put(prefix + "access", null); store.put(prefix + "refresh", null) }
}

// ---- gọi gọn cho kiểu thường gặp (KeyValueStore chỉ lưu chuỗi) ----

fun KeyValueStore.getBool(key: String, default: Boolean = false): Boolean = get(key)?.let { it == "1" } ?: default
fun KeyValueStore.putBool(key: String, value: Boolean) = put(key, if (value) "1" else "0")
fun KeyValueStore.getInt(key: String, default: Int = 0): Int = get(key)?.toIntOrNull() ?: default
fun KeyValueStore.putInt(key: String, value: Int) = put(key, value.toString())

/** Đọc một đối tượng đã lưu dạng JSON; hỏng/không có thì trả null (không bao giờ ném lỗi vì dữ liệu cũ). */
fun <T> KeyValueStore.getJson(key: String, serializer: KSerializer<T>, json: Json = Json { ignoreUnknownKeys = true }): T? =
    get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

fun <T> KeyValueStore.putJson(key: String, serializer: KSerializer<T>, value: T?, json: Json = Json { ignoreUnknownKeys = true }) =
    put(key, value?.let { json.encodeToString(serializer, it) })

/** Địa chỉ máy chủ đổi được lúc chạy (màn đăng nhập dev), nhớ qua lần mở sau. */
class StoredServerUrl(private val prefs: KeyValueStore, private val default: String, private val key: String = "server_url") {
    fun get(): String = prefs.get(key)?.takeIf { it.isNotBlank() } ?: default
    fun set(url: String) = prefs.put(key, normaliseServerUrl(url).ifEmpty { null })
}

/** Bỏ khoảng trắng và dấu `/` cuối; thiếu giao thức thì coi là https. */
fun normaliseServerUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
    return url
}

package kmp.bootstrap.appkit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/*
 * Cache phản hồi GET, BẬT THEO TỪNG LỜI GỌI (mặc định tắt — không ai bị dữ liệu cũ bất ngờ):
 *
 *     client.get<Profile>("/api/me", cache = CachePolicy(ttl = 1.hours))      // trong 1 giờ không gọi lại server
 *     client.get<Feed>("/api/feed", cache = CachePolicy.minutes(5))
 *     client.get<Profile>("/api/me", cache = CachePolicy(1.hours, refresh = true))   // bỏ qua cache lần này, rồi lưu bản mới
 *
 * Quy tắc:
 *  - Chỉ cache GET thành công (2xx). Lỗi không bao giờ vào cache.
 *  - `staleIfError` (mặc định bật): hết hạn mà server lỗi/mất mạng thì trả bản cũ thay vì báo lỗi — app dùng được khi offline.
 *  - Mỗi người dùng một ngăn riêng (`cache.scope = userId`): đổi tài khoản không thấy dữ liệu của người trước.
 *  - Ghi thành công (POST/PUT/PATCH/DELETE) tự xoá cache của tài nguyên đó (xem [ResponseCache.prefixesToInvalidate]).
 *  - Đăng xuất hoặc hết phiên xoá sạch cache của mọi người dùng.
 */
data class CachePolicy(val ttl: Duration, val staleIfError: Boolean = true, val refresh: Boolean = false) {
    companion object {
        fun minutes(n: Int) = CachePolicy(Duration.parse("${n}m"))
        fun hours(n: Int) = CachePolicy(n.hours)
    }
}

@Serializable
data class CacheEntry(val key: String, val path: String, val status: Int, val body: String, val requestId: String?, val storedAtMs: Long)

/** Nơi lưu thật. [MemoryCacheStore] (mất khi tắt app) hoặc [FileCacheStore] (còn sau khi mở lại app). */
interface CacheStore {
    suspend fun get(key: String): CacheEntry?
    suspend fun put(entry: CacheEntry)
    suspend fun remove(key: String)
    suspend fun all(): List<CacheEntry>
    suspend fun clear()
}

/** Trong bộ nhớ, giữ tối đa [maxEntries] mục; đầy thì bỏ mục dùng lâu nhất (LRU). */
class MemoryCacheStore(private val maxEntries: Int = 200) : CacheStore {
    private val map = LinkedHashMap<String, CacheEntry>()
    private val lock = Mutex()
    override suspend fun get(key: String) = lock.withLock { map.remove(key)?.also { map[key] = it } }
    override suspend fun put(entry: CacheEntry) = lock.withLock {
        map.remove(entry.key); map[entry.key] = entry
        while (map.size > maxEntries) map.remove(map.keys.first())
        Unit
    }
    override suspend fun remove(key: String) = lock.withLock { map.remove(key); Unit }
    override suspend fun all() = lock.withLock { map.values.toList() }
    override suspend fun clear() = lock.withLock { map.clear() }
}

/** Mỗi mục một file JSON trong [dir] (xem [platformCacheDir]); còn sau khi mở lại app. File hỏng bị bỏ qua, không làm app đổ. */
class FileCacheStore(private val dir: Path, private val fs: FileSystem = FileSystem.SYSTEM, private val maxEntries: Int = 500) : CacheStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    init { fs.createDirectories(dir) }

    private suspend fun fileOf(key: String): Path = dir / (AppCrypto.sha256(key.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + ".json")

    private fun read(f: Path): CacheEntry? = runCatching { json.decodeFromString(CacheEntry.serializer(), fs.source(f).buffer().use { it.readUtf8() }) }.getOrNull()

    override suspend fun get(key: String) = lock.withLock { fileOf(key).takeIf { fs.exists(it) }?.let(::read) }
    override suspend fun put(entry: CacheEntry) {
        val f = fileOf(entry.key)
        lock.withLock {
            fs.sink(f).buffer().use { it.writeUtf8(json.encodeToString(CacheEntry.serializer(), entry)) }
            val files = fs.list(dir)
            if (files.size > maxEntries) files.sortedBy { fs.metadataOrNull(it)?.lastModifiedAtMillis ?: 0L }.take(files.size - maxEntries).forEach { fs.delete(it) }
        }
    }
    override suspend fun remove(key: String) { val f = fileOf(key); lock.withLock { if (fs.exists(f)) fs.delete(f) } }
    override suspend fun all() = lock.withLock { fs.list(dir).mapNotNull(::read) }
    override suspend fun clear() = lock.withLock { fs.list(dir).forEach { fs.delete(it) } }
}

/** Thư mục cache theo nền tảng (Android: cacheDir · iOS: Library/Caches · desktop: ~/.appkit/cache). Hệ điều hành được phép dọn. */
expect fun platformCacheDir(appName: String): Path

class ResponseCache(
    private val store: CacheStore = MemoryCacheStore(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** Ngăn riêng cho từng người dùng — đặt `cache.scope = user.id` sau đăng nhập. */
    var scope: String = ""

    private fun keyOf(path: String) = "$scope|GET|$path"

    /** Bản cache còn trong hạn (hoặc, với [allowStale], kể cả đã hết hạn). */
    suspend fun lookup(path: String, policy: CachePolicy, allowStale: Boolean = false): CacheEntry? {
        val e = store.get(keyOf(path)) ?: return null
        val fresh = now() - e.storedAtMs < policy.ttl.inWholeMilliseconds
        return if (fresh || allowStale) e else null
    }

    suspend fun save(path: String, r: RawResponse) {
        if (r.status !in 200..299 || r.body.length > MAX_BODY) return
        store.put(CacheEntry(keyOf(path), path, r.status, r.body, r.requestId, now()))
    }

    /** Xoá mọi mục của người dùng hiện tại có đường dẫn bắt đầu bằng [prefix]. */
    suspend fun invalidate(prefix: String) = store.all().filter { it.key.startsWith("$scope|GET|") && it.path.startsWith(prefix) }.forEach { store.remove(it.key) }

    /** Xoá sạch cache của MỌI người dùng (đăng xuất / hết phiên). */
    suspend fun clear() = store.clear()

    /**
     * Ghi vào `/api/items/9/archive` thì xoá cache của `/api/items` (cả danh sách lẫn chi tiết): lấy phần đường dẫn đến trước đoạn đầu tiên trông như
     * một ID (toàn số, hoặc UUID/chuỗi hex dài). Không có ID thì xoá đúng đường dẫn đó. Muốn khác thì gọi [invalidate] tay.
     */
    fun prefixesToInvalidate(writePath: String): List<String> {
        val path = writePath.substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }
        val cut = segments.indexOfFirst { it.all(Char::isDigit) || (it.length >= 16 && it.all { c -> c.isLetterOrDigit() || c == '-' }) }
        val root = if (cut > 0) segments.take(cut) else segments
        return listOf("/" + root.joinToString("/"))
    }

    private companion object { const val MAX_BODY = 1_000_000 }
}

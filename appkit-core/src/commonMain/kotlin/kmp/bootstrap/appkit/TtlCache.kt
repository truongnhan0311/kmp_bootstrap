package kmp.bootstrap.appkit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Cache dùng ở BẤT KỲ ĐÂU (không dính HTTP): giữ giá trị theo khoá, hết hạn sau [ttl], tối đa [maxEntries] (đầy thì bỏ mục dùng lâu nhất).
 *
 *     val profiles = TtlCache<String, Profile>(ttl = 1.hours)
 *     val p = profiles.getOrLoad(userId) { api.fetchProfile(userId) }     // trong 1 giờ không gọi lại
 *     profiles.put(userId, updated)                                        // đặt tay
 *     profiles.remove(userId); profiles.clear()
 *
 * `getOrLoad` an toàn khi nhiều nơi gọi cùng lúc: cùng một khoá chỉ chạy MỘT lần tải, những nơi còn lại chờ chung kết quả
 * (không bắn N request giống nhau). Lỗi khi tải không bao giờ được lưu; với [staleIfError] thì trả bản cũ đã hết hạn thay vì ném lỗi.
 */
class TtlCache<K : Any, V : Any>(
    private val ttl: Duration = 1.hours,
    private val maxEntries: Int = 200,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Item<V>(val value: V, val expiresAt: Long)

    private val items = LinkedHashMap<K, Item<V>>()
    private val loading = HashMap<K, CompletableDeferred<V>>()
    private val lock = Mutex()

    /** Giá trị còn hạn, hoặc null. */
    suspend fun get(key: K): V? = lock.withLock { fresh(key) }

    suspend fun put(key: K, value: V, ttl: Duration = this.ttl) = lock.withLock { store(key, value, ttl) }

    suspend fun remove(key: K) = lock.withLock { items.remove(key); Unit }
    suspend fun clear() = lock.withLock { items.clear() }
    /** Xoá mọi mục thoả điều kiện (ví dụ khoá bắt đầu bằng một tiền tố). */
    suspend fun removeIf(predicate: (K) -> Boolean) = lock.withLock { items.keys.filter(predicate).forEach { items.remove(it) } }
    suspend fun size(): Int = lock.withLock { items.values.count { it.expiresAt > now() } }

    /**
     * Còn hạn thì trả ngay; không thì chạy [loader] (một lần cho mọi người gọi cùng khoá), lưu rồi trả.
     * [staleIfError]: loader lỗi (trừ bị huỷ) mà có bản đã hết hạn → trả bản đó.
     */
    suspend fun getOrLoad(key: K, ttl: Duration = this.ttl, staleIfError: Boolean = false, loader: suspend () -> V): V {
        while (true) {
            var mine: CompletableDeferred<V>? = null
            var waitFor: CompletableDeferred<V>? = null
            val stale: V?
            lock.withLock {
                fresh(key)?.let { return it }
                stale = items[key]?.value
                val running = loading[key]
                if (running != null) waitFor = running else { mine = CompletableDeferred<V>().also { loading[key] = it } }
            }
            val w = waitFor
            if (w != null) {
                try { return w.await() } catch (e: CancellationException) {
                    if (!currentCoroutineContextActive()) throw e
                    continue // người tải bị huỷ → thử lại, lần này mình làm người tải
                }
            }
            val d = mine!!
            try {
                val v = loader()
                lock.withLock { store(key, v, ttl); loading.remove(key) }
                d.complete(v)
                return v
            } catch (e: CancellationException) {
                lock.withLock { loading.remove(key) }; d.cancel(e); throw e
            } catch (e: Throwable) {
                lock.withLock { loading.remove(key) }; d.completeExceptionally(e)
                if (staleIfError && stale != null) return stale
                throw e
            }
        }
    }

    /** Giá trị còn hạn. Mục hết hạn được GIỮ LẠI (để [staleIfError] còn dùng được) cho tới khi cache đầy hoặc bị ghi đè. */
    private fun fresh(key: K): V? {
        val item = items[key] ?: return null
        if (item.expiresAt <= now()) return null
        items.remove(key); items[key] = item // đưa lên cuối = vừa dùng
        return item.value
    }

    private fun store(key: K, value: V, ttl: Duration) {
        items.remove(key); items[key] = Item(value, now() + ttl.inWholeMilliseconds)
        if (items.size > maxEntries) items.entries.removeAll { it.value.expiresAt <= now() } // đầy: bỏ mục hết hạn trước
        while (items.size > maxEntries) items.remove(items.keys.first())
    }
}

private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive ?: true

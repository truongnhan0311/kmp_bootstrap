package kmp.bootstrap.appkit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TtlCacheTest {
    private var now = 0L
    private fun cache(max: Int = 200) = TtlCache<String, String>(1.hours, max, now = { now })

    @Test fun valuesExpireAfterTheTtl() = runTest {
        val c = cache(); c.put("a", "1")
        now += 59.minutes.inWholeMilliseconds; assertEquals("1", c.get("a"))
        now += 2.minutes.inWholeMilliseconds; assertNull(c.get("a")); assertEquals(0, c.size())
    }

    @Test fun getOrLoadLoadsOncePerTtlAndConcurrentCallersShareOneLoad() = runTest {
        val c = cache(); var loads = 0
        val results = (1..6).map { async { c.getOrLoad("k") { loads++; delay(100); "v$loads" } } }.awaitAll()
        assertEquals(1, loads, "6 nơi gọi cùng lúc chỉ tải một lần"); assertEquals(setOf("v1"), results.toSet())
        c.getOrLoad("k") { loads++; "x" }; assertEquals(1, loads, "còn hạn thì không tải lại")
        now += 2.hours.inWholeMilliseconds; assertEquals("v2", c.getOrLoad("k") { loads++; "v$loads" })
    }

    @Test fun failedLoadsAreNotStoredAndStaleIsServedOnRequest() = runTest {
        val c = cache(); assertFailsWith<IllegalStateException> { c.getOrLoad("k") { error("boom") } }
        assertNull(c.get("k"), "lỗi không vào cache"); assertEquals("ok", c.getOrLoad("k") { "ok" })
        now += 2.hours.inWholeMilliseconds
        assertEquals("ok", c.getOrLoad("k", staleIfError = true) { error("down") }, "lỗi mà có bản cũ → trả bản cũ")
        assertFailsWith<IllegalStateException> { c.getOrLoad("k") { error("down") } }
    }

    @Test fun oldestEntryIsEvictedWhenFullAndUseKeepsAnEntryAlive() = runTest {
        val c = cache(max = 2); c.put("a", "1"); c.put("b", "2"); c.get("a"); c.put("c", "3")
        assertNull(c.get("b"), "b dùng lâu nhất → bị bỏ"); assertEquals("1", c.get("a")); assertEquals("3", c.get("c"))
    }

    @Test fun perEntryTtlAndRemoveIfWork() = runTest {
        val c = cache(); c.put("short", "1", ttl = 1.minutes); c.put("user:1", "a"); c.put("user:2", "b"); c.put("other", "c")
        now += 2.minutes.inWholeMilliseconds; assertNull(c.get("short")); assertEquals("a", c.get("user:1"))
        c.removeIf { it.startsWith("user:") }; assertNull(c.get("user:2")); assertEquals("c", c.get("other"))
    }
}

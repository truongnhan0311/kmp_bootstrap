package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Serializable private data class Profile(val name: String)

class CacheTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var now = 1_000_000L
    private var calls = 0
    private var status = HttpStatusCode.OK
    private var failNetwork = false
    private var name = "v1"

    private fun client(store: CacheStore = MemoryCacheStore()): AuthedClient {
        val engine = MockEngine { req ->
            calls++
            if (failNetwork) throw RuntimeException("Unable to resolve host")
            if (req.method.value == "GET") respond("""{"name":"$name"}""", status, json) else respond("{}", HttpStatusCode.OK, json)
        }
        return AuthedClient(HttpTransport("https://x.test", "app", engine, getRetries = 0), MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
            .also { it.cache = ResponseCache(store, now = { now }) }
    }

    @Test fun withinTheTtlTheServerIsNotCalledAgain() = runTest {
        val c = client(); val oneHour = CachePolicy(1.hours)
        assertEquals("v1", c.get<Profile>("/me", cache = oneHour).name); name = "v2"
        now += 59.minutes.inWholeMilliseconds
        assertEquals("v1", c.get<Profile>("/me", cache = oneHour).name, "còn trong 1 giờ → bản cũ, không gọi mạng"); assertEquals(1, calls)
        now += 2.minutes.inWholeMilliseconds
        assertEquals("v2", c.get<Profile>("/me", cache = oneHour).name, "quá 1 giờ → gọi lại"); assertEquals(2, calls)
    }

    @Test fun cachingIsOptInPerCall() = runTest {
        val c = client(); c.get<Profile>("/me"); c.get<Profile>("/me")
        assertEquals(2, calls, "không truyền cache = luôn gọi server")
    }

    @Test fun refreshSkipsTheCacheButStoresTheNewCopy() = runTest {
        val c = client(); c.get<Profile>("/me", cache = CachePolicy(1.hours)); name = "v2"
        assertEquals("v2", c.get<Profile>("/me", cache = CachePolicy(1.hours, refresh = true)).name)
        assertEquals("v2", c.get<Profile>("/me", cache = CachePolicy(1.hours)).name); assertEquals(2, calls)
    }

    @Test fun staleCopyIsServedWhenOfflineOrServerDownButNotWhenDisabled() = runTest {
        val c = client(); c.get<Profile>("/me", cache = CachePolicy(1.hours)); now += 2.hours.inWholeMilliseconds
        failNetwork = true
        assertEquals("v1", c.get<Profile>("/me", cache = CachePolicy(1.hours)).name, "offline → bản cũ")
        assertFailsWith<ApiError> { c.get<Profile>("/me", cache = CachePolicy(1.hours, staleIfError = false)) }
        failNetwork = false; status = HttpStatusCode.InternalServerError
        assertEquals("v1", c.get<Profile>("/me", cache = CachePolicy(1.hours)).name, "5xx → bản cũ")
    }

    @Test fun errorsAreNeverCachedAnd404IsNotMaskedByStale() = runTest {
        val c = client(); status = HttpStatusCode.NotFound
        assertFailsWith<ApiError> { c.get<Profile>("/me", cache = CachePolicy(1.hours)) }
        status = HttpStatusCode.OK; assertEquals("v1", c.get<Profile>("/me", cache = CachePolicy(1.hours)).name, "404 trước đó không vào cache")
    }

    @Test fun aSuccessfulWriteDropsTheCachedResource() = runTest {
        val c = client(); c.get<Profile>("/api/items", cache = CachePolicy(1.hours)); c.get<Profile>("/api/items/9", cache = CachePolicy(1.hours)); c.get<Profile>("/api/other", cache = CachePolicy(1.hours))
        assertEquals(3, calls); name = "v2"
        c.sendNoContent("POST", "/api/items/9/archive")
        assertEquals("v2", c.get<Profile>("/api/items", cache = CachePolicy(1.hours)).name, "danh sách bị xoá cache")
        assertEquals("v2", c.get<Profile>("/api/items/9", cache = CachePolicy(1.hours)).name, "chi tiết cũng vậy")
        assertEquals("v1", c.get<Profile>("/api/other", cache = CachePolicy(1.hours)).name, "tài nguyên khác giữ nguyên")
    }

    @Test fun usersNeverSeeEachOthersCacheAndLogoutWipesEverything() = runTest {
        val c = client(); c.cache.scope = "alice"; c.get<Profile>("/me", cache = CachePolicy(1.hours)); name = "bob-data"
        c.cache.scope = "bob"
        assertEquals("bob-data", c.get<Profile>("/me", cache = CachePolicy(1.hours)).name, "ngăn riêng của bob")
        c.logout(); c.cache.scope = "alice"; name = "after"
        assertEquals("after", c.get<Profile>("/me", cache = CachePolicy(1.hours)).name, "đăng xuất xoá cả cache của alice")
    }

    @Test fun invalidationPrefixesFollowTheResourceNotTheAction() {
        val c = ResponseCache()
        assertEquals(listOf("/api/items"), c.prefixesToInvalidate("/api/items/9/archive"))
        assertEquals(listOf("/api/items"), c.prefixesToInvalidate("/api/items"))
        assertEquals(listOf("/api/v2/me/profile"), c.prefixesToInvalidate("/api/v2/me/profile?x=1"))
        assertEquals(listOf("/api/users"), c.prefixesToInvalidate("/api/users/3f2b8c1e-9d4a-4e7b-8c2a-1b2c3d4e5f60/follow"))
    }

    @Test fun fileStoreSurvivesRestartAndMemoryStoreEvictsOldest() = runTest {
        val fs = FakeFileSystem(); val dir = "/cache".toPath()
        var t = 5L
        ResponseCache(FileCacheStore(dir, fs), now = { t }).save("/me", RawResponse(200, """{"name":"disk"}""", "r1"))
        val again = ResponseCache(FileCacheStore(dir, fs), now = { t })
        assertEquals("disk", again.lookup("/me", CachePolicy(1.hours))?.body?.substringAfter("\":\"")?.substringBefore('"'))
        again.clear(); assertNull(again.lookup("/me", CachePolicy(1.hours)))
        val mem = MemoryCacheStore(maxEntries = 2)
        listOf("a", "b", "c").forEach { mem.put(CacheEntry(it, "/$it", 200, "x", null, 0)) }
        assertNull(mem.get("a")); assertTrue(mem.get("c") != null)
        fs.createDirectories(dir); fs.write(dir / "broken.json") { writeUtf8("{không phải json") }
        assertTrue(FileCacheStore(dir, fs).all().isEmpty(), "file hỏng bị bỏ qua, không làm app đổ")
    }
}

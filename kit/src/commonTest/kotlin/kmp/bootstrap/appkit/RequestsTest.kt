package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable private data class Item(val id: Int, val name: String)

class RequestsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun client(retries: Int = 1, handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        AuthedClient(HttpTransport("https://x.test", "app", MockEngine(handler), getRetries = retries, backoffMs = 0), MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)

    @Test fun decodesJsonAndIgnoresUnknownFields() = runTest {
        val c = client { respond("""{"id":1,"name":"x","extra":true}""", HttpStatusCode.OK, json) }
        assertEquals(Item(1, "x"), c.get<Item>("/i"))
    }

    @Test fun notFoundIsAKindAndGetOrNullTurnsItIntoNull() = runTest {
        val c = client { respond("""{"message":"không có"}""", HttpStatusCode.NotFound, json) }
        val e = assertFailsWith<ApiError> { c.get<Item>("/i/9") }
        assertEquals(ErrorKind.NotFound, e.kind); assertEquals("không có", e.message); assertTrue(e.isNotFound)
        assertNull(c.getOrNull<Item>("/i/9"))
    }

    @Test fun otherErrorsStillThrowFromGetOrNull() = runTest {
        val c = client(retries = 0) { respond("{}", HttpStatusCode.Forbidden, json) }
        assertEquals(ErrorKind.Forbidden, assertFailsWith<ApiError> { c.getOrNull<Item>("/i") }.kind)
    }

    @Test fun badJsonIsAParseErrorNotARawCrash() = runTest {
        val c = client { respond("<html>oops</html>", HttpStatusCode.OK, json) }
        val e = assertFailsWith<ApiError> { c.get<Item>("/i") }
        assertEquals(ErrorKind.Parse, e.kind)
    }

    @Test fun statusCodesMapToKinds() = runTest {
        for ((code, kind) in listOf(422 to ErrorKind.Invalid, 409 to ErrorKind.Conflict, 429 to ErrorKind.RateLimited, 500 to ErrorKind.Server, 418 to ErrorKind.Other)) {
            val c = client(retries = 0) { respond("{}", HttpStatusCode.fromValue(code), json) }
            assertEquals(kind, assertFailsWith<ApiError> { c.post<Item>("/i") }.kind, "HTTP $code")
        }
    }

    @Test fun getIsRetriedOnServerErrorButWriteNeverIs() = runTest {
        var gets = 0; var posts = 0
        val c = client(retries = 2) { req ->
            if (req.method.value == "GET") { gets++; if (gets < 3) respond("{}", HttpStatusCode.ServiceUnavailable, json) else respond("""{"id":1,"name":"ok"}""", HttpStatusCode.OK, json) }
            else { posts++; respond("{}", HttpStatusCode.InternalServerError, json) }
        }
        assertEquals("ok", c.get<Item>("/i").name); assertEquals(3, gets, "thử lại 2 lần rồi thành công")
        assertFailsWith<ApiError> { c.post<Item>("/i", buildJsonObject { put("a", 1) }) }
        assertEquals(1, posts, "ghi không bao giờ tự thử lại (tránh ghi trùng)")
    }

    @Test fun timeoutAndNetworkAreDistinguishedAndRetryable() = runTest {
        val slow = client(retries = 0) { throw io.ktor.client.plugins.HttpRequestTimeoutException("https://x.test/i", 1) }
        val t = assertFailsWith<ApiError> { slow.get<Item>("/i") }
        assertEquals(ErrorKind.Timeout, t.kind); assertTrue(t.isTimeout && t.isNetwork && t.isRetryable)
        val down = client(retries = 0) { throw RuntimeException("Unable to resolve host x.test") }
        val n = assertFailsWith<ApiError> { down.get<Item>("/i") }
        assertEquals(ErrorKind.Network, n.kind); assertEquals(0, n.status)
    }

    @Test fun deleteNeedsNoBodyAndFailsLoudly() = runTest {
        client { respond("", HttpStatusCode.NoContent) }.delete("/i/1")
        assertFailsWith<ApiError> { client { respond("{}", HttpStatusCode.NotFound, json) }.delete("/i/1") }
    }

    @Test fun errorMessageComesFromTheBackendContract() = runTest {
        val c = AuthedClient(HttpTransport("https://x.test", "app", MockEngine { respond("""{"code":400,"message":"wrong_password","data":""}""", HttpStatusCode.BadRequest, json) }, getRetries = 0),
            MemoryTokenStore(), EnvelopeContract(Endpoints.qtDepot()), refreshMarginSeconds = 0)
        assertEquals("wrong_password", assertFailsWith<ApiError> { c.post<Item>("/login", auth = false) }.message)
    }
}

@Serializable private data class NewItem(val name: String, val tags: List<String> = emptyList())

class TypedBodyTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun serializableBodyIsSentAsJsonWithJwtOnEveryVerb() = runTest {
        val seen = mutableListOf<Triple<String, String?, String>>()
        val engine = MockEngine { req ->
            seen += Triple(req.method.value, req.headers[HttpHeaders.Authorization], req.body.toString().let { (req.body as? io.ktor.http.content.TextContent)?.text ?: it })
            respond("""{"id":7,"name":"ok"}""", HttpStatusCode.OK, json)
        }
        val c = AuthedClient(HttpTransport("https://x.test", "app", engine), MemoryTokenStore("jwt1", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        val a: Item = c.post("/i", NewItem("x", listOf("a")))
        val b: Item = c.put("/i/7", NewItem("y"))
        val p: Item = c.patch("/i/7", NewItem("z"))
        c.delete("/i/7")
        c.sendNoContent("POST", "/i/7/archive")
        assertEquals(listOf("POST", "PUT", "PATCH", "DELETE", "POST"), seen.map { it.first })
        assertTrue(seen.all { it.second == "Bearer jwt1" }, "JWT được gắn ở mọi lời gọi, không phải truyền tay")
        assertTrue(seen[0].third.contains("\"name\":\"x\"") && seen[0].third.contains("\"tags\":[\"a\"]"), seen[0].third)
        assertEquals(7, a.id + b.id - 7 + p.id - 7 + 0 * 0)
    }

    @Test fun anExpiredJwtIsRefreshedAndTheWriteIsResentOnce() = runTest {
        var posts = 0; var refreshes = 0
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/refresh") -> { refreshes++; respond("""{"access_token":"fresh","refresh_token":"r2"}""", HttpStatusCode.OK, json) }
                req.headers[HttpHeaders.Authorization] == "Bearer fresh" -> { posts++; respond("""{"id":1,"name":"saved"}""", HttpStatusCode.OK, json) }
                else -> respond("{}", HttpStatusCode.Unauthorized, json)
            }
        }
        val c = AuthedClient(HttpTransport("https://x.test", "app", engine), MemoryTokenStore("expired", "r1"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        val saved: Item = c.post("/items", NewItem("x"))
        assertEquals("saved", saved.name); assertEquals(1, refreshes); assertEquals(1, posts, "ghi đúng một lần sau khi làm mới")
    }
}

class FailureLoggingTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val lines = mutableListOf<Pair<LogLevel, String>>()
    @kotlin.test.BeforeTest fun capture() { AppLog.install(object : LogSink { override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) { lines += level to message } }) }
    @kotlin.test.AfterTest fun release() { AppLog.install() }

    private fun client(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        AuthedClient(HttpTransport("https://x.test", "app", MockEngine(handler), getRetries = 0), MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)

    @Test fun serverErrorIsLoggedAsWarnWithRequestIdAndKind() = runTest {
        val c = client { respond("""{"message":"boom"}""", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Request-Id" to listOf("req-42"))) }
        assertFailsWith<ApiError> { c.get<Item>("/items") }
        val failed = lines.single { it.second.contains("FAILED") }
        assertEquals(LogLevel.Warn, failed.first)
        listOf("GET /items", "kind=Server", "status=500", "req=req-42", "boom").forEach { assertTrue(failed.second.contains(it), "$it ∈ ${failed.second}") }
    }

    @Test fun expectedClientErrorsAreInfoNotNoise() = runTest {
        val c = client { respond("{}", HttpStatusCode.NotFound, json) }
        assertNull(c.getOrNull<Item>("/items/9"))
        assertEquals(LogLevel.Info, lines.single { it.second.contains("FAILED") }.first)
    }

    @Test fun networkTimeoutAndParseFailuresLeaveATrace() = runTest {
        val down = client { throw RuntimeException("Unable to resolve host x.test") }
        assertFailsWith<ApiError> { down.get<Item>("/a") }
        val slow = client { throw io.ktor.client.plugins.HttpRequestTimeoutException("https://x.test/b", 1) }
        assertFailsWith<ApiError> { slow.get<Item>("/b") }
        val bad = client { respond("<html>", HttpStatusCode.OK, json) }
        assertFailsWith<ApiError> { bad.get<Item>("/c") }
        val text = lines.map { it.second }.filter { it.contains("FAILED") }
        assertTrue(text.any { it.contains("kind=Network") && it.contains("/a") && it.contains("cause=") })
        assertTrue(text.any { it.contains("kind=Timeout") && it.contains("/b") })
        assertTrue(text.any { it.contains("kind=Parse") && it.contains("/c") })
        assertEquals(LogLevel.Error, lines.first { it.second.contains("kind=Parse") }.first)
    }

    @Test fun oneFailureIsLoggedExactlyOnce() = runTest {
        assertFailsWith<ApiError> { client { respond("{}", HttpStatusCode.Forbidden, json) }.post<Item>("/x") }
        assertEquals(1, lines.count { it.second.contains("FAILED") })
    }
}

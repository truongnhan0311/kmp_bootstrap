package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

class AuthedClientTest {
    private fun tokensPair(a: String, r: String) = """{"access_token":"$a","refresh_token":"$r"}"""

    @Test
    fun manyConcurrent401sShareOneRefresh() = runTest {
        var refreshCalls = 0
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/refresh") -> { refreshCalls++; respond(tokensPair("new", "r2"), HttpStatusCode.OK, JSON) }
                req.headers[HttpHeaders.Authorization] == "Bearer new" -> respond("""{"ok":true}""", HttpStatusCode.OK, JSON)
                else -> respond("""{"message":"expired"}""", HttpStatusCode.Unauthorized, JSON)
            }
        }
        val tokens = MemoryTokenStore("old", "r1")
        val client = AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        val results = (1..8).map { async { client.raw("GET", "/api/things") } }.awaitAll()
        assertTrue(results.all { it.status == 200 })
        assertEquals(1, refreshCalls, "refresh token dùng một lần: chỉ được đổi một lần")
        assertEquals("new", tokens.access()); assertEquals("r2", tokens.refresh())
    }

    @Test
    fun deadRefreshClearsTokensAndNotifies() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/refresh")) respond("""{"code":"refresh_invalid"}""", HttpStatusCode.Unauthorized, JSON)
            else respond("{}", HttpStatusCode.Unauthorized, JSON)
        }
        val tokens = MemoryTokenStore("old", "r1")
        val client = AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        val expired = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { client.sessionExpired.first() }
        assertEquals(401, client.raw("GET", "/api/me").status)
        expired.await()
        assertNull(tokens.access()); assertNull(tokens.refresh())
    }

    @Test
    fun networkFailureWhileRefreshingKeepsTokens() = runTest {
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/refresh")) throw RuntimeException("boom") else respond("{}", HttpStatusCode.Unauthorized, JSON)
        }
        val tokens = MemoryTokenStore("old", "r1")
        val client = AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        assertEquals(401, client.raw("GET", "/api/me").status)
        assertEquals("old", tokens.access()); assertEquals("r1", tokens.refresh())
    }

    @Test
    fun refreshesBeforeExpiryAndSendsAppId() = runTest {
        // JWT hết hạn sau 30 giây (exp = 1030, now = 1000)
        val jwt = "h." + java_b64("""{"exp":1030}""") + ".s"
        var sawAppId: String? = null; var bearer: String? = null
        val engine = MockEngine { req ->
            if (req.url.encodedPath.endsWith("/refresh")) respond(tokensPair("fresh", "r2"), HttpStatusCode.OK, JSON)
            else { sawAppId = req.headers["X-App-Id"]; bearer = req.headers[HttpHeaders.Authorization]; respond("{}", HttpStatusCode.OK, JSON) }
        }
        val tokens = MemoryTokenStore(jwt, "r1")
        val client = AuthedClient(Failover(listOf("https://x.test")), tokens, PlainJsonContract(TEST_PLAIN), "taper-app", engine, 120) { 1000 }
        client.raw("GET", "/api/me")
        assertEquals("Bearer fresh", bearer); assertEquals("taper-app", sawAppId)
    }

    @Test
    fun authEndpointsAreNeverRefreshedOrGivenABearer() = runTest {
        var calls = 0; var auth: String? = "unset"
        val engine = MockEngine { req -> calls++; auth = req.headers[HttpHeaders.Authorization]; respond("{}", HttpStatusCode.Unauthorized, JSON) }
        val client = AuthedClient("https://x.test", MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        client.raw("POST", "/api/auth/login", null)
        assertEquals(1, calls); assertNull(auth)
    }

    @Test
    fun envelopeContractRescuesATokenRevokedInsideAn200() = runTest {
        var refreshes = 0
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/refresh/token") -> { refreshes++; respond("""{"code":200,"message":"success","data":{"token":"n","refresh_token":"r2"}}""", HttpStatusCode.OK, JSON) }
                req.headers[HttpHeaders.Authorization] == "Bearer n" -> respond("""{"code":200,"data":{}}""", HttpStatusCode.OK, JSON)
                else -> respond("""{"code":400,"message":"Token_Revoked","data":""}""", HttpStatusCode.OK, JSON)
            }
        }
        val tokens = MemoryTokenStore("old", "r1")
        val client = AuthedClient("https://x.test", tokens, EnvelopeContract(Endpoints.qtDepot()), "app", engine, refreshMarginSeconds = 0)
        assertEquals(200, client.raw("GET", "/api/v5/things").status)
        assertEquals(1, refreshes); assertEquals("n", tokens.access())
    }

    @Test
    fun envelopeLoginFailureIsNotTokens() {
        val c = EnvelopeContract(Endpoints.qtDepot())
        assertNull(c.parseTokens(200, """{"code":400,"message":"wrong_password","data":""}"""))
        assertEquals("wrong_password", c.errorMessage(200, """{"code":400,"message":"wrong_password","data":""}"""))
        assertFalse(c.isTokenRejected(200, """{"code":400,"message":"wrong_password"}"""))
    }

    @Test
    fun failoverTriesTheBackupHostWhenUnreachable() = runTest {
        val hosts = mutableListOf<String>()
        val engine = MockEngine { req ->
            hosts += req.url.host
            if (req.url.host == "a.test") throw RuntimeException("Unable to resolve host a.test") else respond("{}", HttpStatusCode.OK, JSON)
        }
        val client = AuthedClient(Failover(listOf("https://a.test", "https://b.test")), MemoryTokenStore(), PlainJsonContract(TEST_PLAIN), "app", engine)
        assertEquals(200, client.raw("GET", "/x", auth = false).status)
        assertEquals(listOf("a.test", "b.test"), hosts)
        client.raw("GET", "/x", auth = false)
        assertEquals("b.test", hosts.last()) // nhớ địa chỉ đang chạy được
        assertEquals(3, hosts.size)
    }

    @Test
    fun refreshNowCanBeCalledFromAnywhereAndSharesOneExchange() = runTest {
        var refreshCalls = 0
        val engine = MockEngine { respond(tokensPair("n${++refreshCalls}", "r$refreshCalls"), HttpStatusCode.OK, JSON) }
        val tokens = MemoryTokenStore("old", "r0")
        val client = AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        assertTrue(client.hasSession())
        val results = (1..5).map { async { client.refreshNow() } }.awaitAll()
        // lần gọi đầu đổi token; các lần sau thấy access đã khác nên dùng luôn
        assertTrue(results.all { it }); assertEquals("n1", tokens.access())
        assertTrue(refreshCalls in 1..5)
        assertFalse(AuthedClient("https://x.test", MemoryTokenStore(), PlainJsonContract(TEST_PLAIN), "app", engine).refreshNow(), "không có phiên thì false")
    }

    @Test
    fun logoutClearsTokensEvenWhenOffline() = runTest {
        val engine = MockEngine { throw RuntimeException("Unable to resolve host") }
        val tokens = MemoryTokenStore("a", "r")
        AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0).logout()
        assertNull(tokens.access()); assertNull(tokens.refresh())
    }

    @Test
    fun storeLoginKeepsOldRefreshWhenServerDoesNotRotate() {
        val tokens = MemoryTokenStore(null, "keep")
        val client = AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app")
        assertTrue(client.storeLogin(RawResponse(200, """{"token":"t"}""", null)))
        assertEquals("t", tokens.access()); assertEquals("keep", tokens.refresh())
        assertFalse(client.storeLogin(RawResponse(401, "{}", null)))
    }

    @Test
    fun logoutSendsTheBearerTokenAndRefreshTokenBody() = runTest {
        var bearer: String? = null
        var body = ""
        val engine = MockEngine { req ->
            bearer = req.headers[HttpHeaders.Authorization]
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("", HttpStatusCode.NoContent)
        }
        val tokens = MemoryTokenStore("access-1", "refresh-1")
        AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0).logout(all = true)
        assertEquals("Bearer access-1", bearer, "server đặt logout sau middleware xác thực: thiếu Bearer là đăng xuất thất bại âm thầm")
        assertTrue(body.contains("\"refresh_token\":\"refresh-1\"") && body.contains("\"all\":true"), body)
        assertNull(tokens.access()); assertNull(tokens.refresh())
    }

    @Test
    fun loginRegisterAndRefreshStillCarryNoBearer() = runTest {
        val seen = mutableListOf<String?>()
        val engine = MockEngine { req -> seen += req.headers[HttpHeaders.Authorization]; respond("{}", HttpStatusCode.OK, JSON) }
        val client = AuthedClient("https://x.test", MemoryTokenStore("old", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        client.raw("POST", TEST_PLAIN.login); client.raw("POST", TEST_PLAIN.register!!); client.raw("POST", TEST_PLAIN.refresh)
        assertEquals(listOf<String?>(null, null, null), seen)
    }

    @Test
    fun tokenIsNeverSentToAThirdPartyAbsoluteUrl() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val engine = MockEngine { req -> seen += req.url.toString() to req.headers[HttpHeaders.Authorization]; respond(byteArrayOf(1), HttpStatusCode.OK) }
        val client = AuthedClient("https://x.test/app", MemoryTokenStore("secret", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        client.getBytes("https://evil.test/a.png")
        client.download("https://evil.test/f.fit")
        client.getBytes("https://x.test/app/avatars/1")   // thuộc máy chủ của mình: có token
        client.getBytes("/avatars/2")                       // đường tương đối: có token
        assertEquals(listOf(null, null, "Bearer secret", "Bearer secret"), seen.map { it.second })
        assertEquals("https://evil.test/a.png", seen[0].first, "URL tuyệt đối dùng nguyên, không nối vào địa chỉ máy chủ")
        assertEquals("https://x.test/app/avatars/2", seen[3].first)
    }

    @Test
    fun serverAddressCanChangeAtRuntimeWithoutLosingTheSession() = runTest {
        var base = "https://one.test"
        val hosts = mutableListOf<String>(); val bearers = mutableListOf<String?>()
        val engine = MockEngine { req -> hosts += req.url.host; bearers += req.headers[HttpHeaders.Authorization]; respond("{}", HttpStatusCode.OK, JSON) }
        val client = AuthedClient(HttpTransport(Failover({ listOf(base) }), "app", engine), MemoryTokenStore("jwt", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        client.raw("GET", "/api/a")
        base = "https://two.test"
        client.raw("GET", "/api/b")
        assertEquals(listOf("one.test", "two.test"), hosts)
        assertEquals(listOf<String?>("Bearer jwt", "Bearer jwt"), bearers)
    }

    @Test
    fun refreshMarginAndGetRetriesAreConfigurable() = runTest {
        // exp = now + 30s; margin 10s → KHÔNG làm mới sớm; margin 120s → làm mới sớm.
        val jwt = "h." + java_b64("""{"exp":1030}""") + ".s"
        suspend fun run(margin: Long): Int {
            var refreshes = 0
            val engine = MockEngine { req ->
                if (req.url.encodedPath.endsWith("/refresh")) { refreshes++; respond(tokensPair("fresh", "r2"), HttpStatusCode.OK, JSON) } else respond("{}", HttpStatusCode.OK, JSON)
            }
            val client = AuthedClient("https://x.test", MemoryTokenStore(jwt, "r1"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = margin, nowEpochSeconds = { 1000 })
            client.raw("GET", "/api/a")
            return refreshes
        }
        assertEquals(0, run(10)); assertEquals(1, run(120))

        var calls = 0
        val failing = MockEngine { calls++; respond("{}", HttpStatusCode.InternalServerError, JSON) }
        AuthedClient(HttpTransport("https://x.test", "app", failing, getRetries = 0, backoffMs = 1), MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0).raw("GET", "/api/a")
        assertEquals(1, calls, "getRetries = 0 tắt thử lại")
    }
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun java_b64(s: String) = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT).encode(s.encodeToByteArray())

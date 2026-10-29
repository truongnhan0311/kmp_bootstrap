package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable private data class Me(val name: String)

class SessionTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private var meStatus = HttpStatusCode.OK
    private var offline = false
    private val seen = mutableListOf<String>()

    private fun session(tokens: TokenStore = MemoryTokenStore("a", "r"), prefs: KeyValueStore? = MemoryKeyValueStore()): Pair<Session<Me>, AuthedClient> {
        val engine = MockEngine { req ->
            seen += req.method.value + " " + req.url.encodedPath
            if (offline) throw RuntimeException("Unable to resolve host")
            when {
                req.url.encodedPath.endsWith("/refresh") -> respond("""{"code":"refresh_invalid"}""", HttpStatusCode.Unauthorized, json)
                req.url.encodedPath.endsWith("/me") -> respond("""{"name":"Nhan"}""", meStatus, json)
                req.url.encodedPath.endsWith("/login") -> respond("""{"access_token":"jwt","refresh_token":"ref"}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        val client = AuthedClient(HttpTransport("https://x.test", "app", engine, getRetries = 0), tokens, PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        return Session(client, { client.get<Me>("/api/me") }, Me.serializer(), prefs, CoroutineScope(Dispatchers.Unconfined)) to client
    }

    @Test fun startsLoadingThenSignedOutWhenThereIsNoSession() = runTest {
        val (s, _) = session(MemoryTokenStore()); assertEquals(SessionState.Loading, s.state.value)
        s.restore(); assertEquals(SessionState.SignedOut, s.state.value)
    }

    @Test fun restoreWithASessionLoadsTheUserAndRunsTheHook() = runTest {
        val (s, _) = session(); var hooked: Me? = null; s.onSignedIn = { hooked = it }
        s.restore(); assertEquals(SessionState.SignedIn(Me("Nhan")), s.state.value); assertEquals(Me("Nhan"), hooked); assertEquals("Nhan", s.user?.name)
    }

    @Test fun unauthorizedWhileRestoringSignsOutAndClearsTokens() = runTest {
        val tokens = MemoryTokenStore("a", "r"); val (s, _) = session(tokens); meStatus = HttpStatusCode.Unauthorized
        s.restore(); assertEquals(SessionState.SignedOut, s.state.value); assertNull(tokens.access()); assertNull(tokens.refresh())
    }

    @Test fun offlineRestoreUsesTheSavedUserInsteadOfKickingToLogin() = runTest {
        val prefs = MemoryKeyValueStore(); val (s1, _) = session(prefs = prefs); s1.restore()      // lần mở trước: online
        offline = true
        val (s2, _) = session(prefs = prefs); s2.restore()
        assertEquals(SessionState.SignedIn(Me("Nhan")), s2.state.value, "mất mạng mà đã có phiên → vào app với người dùng đã lưu")
        val (s3, _) = session(prefs = MemoryKeyValueStore()); s3.restore()
        assertEquals(SessionState.SignedOut, s3.state.value, "chưa từng lưu người dùng thì không có gì để hiện")
    }

    @Test fun signInStoresTokensLoadsTheUserAndFailedLoginIsReportedNotThrown() = runTest {
        val (s, c) = session(MemoryTokenStore())
        assertFalse(s.signIn(RawResponse(401, """{"message":"bad"}""", null))); assertEquals(SessionState.Loading, s.state.value)
        assertTrue(s.signIn(c.raw("POST", TEST_PLAIN.login, auth = false))); assertEquals(SessionState.SignedIn(Me("Nhan")), s.state.value); assertTrue(c.hasSession())
    }

    @Test fun signOutRunsCleanupFirstThenRevokesAndForgets() = runTest {
        val tokens = MemoryTokenStore("a", "r"); val prefs = MemoryKeyValueStore(); val (s, _) = session(tokens, prefs); s.restore()
        val order = mutableListOf<String>()
        s.signOut(beforeLogout = { order += "cleanup(" + (tokens.access() ?: "no-token") + ")" })
        assertEquals(listOf("cleanup(a)"), order, "việc dọn chạy khi còn token"); assertEquals(SessionState.SignedOut, s.state.value)
        assertNull(tokens.access()); assertNull(prefs.get("session.user")); assertTrue(seen.any { it.endsWith("/logout") })
    }

    @Test fun aFailingCleanupNeverBlocksSigningOut() = runTest {
        val (s, _) = session(); s.restore(); s.signOut(beforeLogout = { error("push server down") }); assertEquals(SessionState.SignedOut, s.state.value)
    }

    @Test fun expiredRefreshTokenAnywhereInTheAppSignsOut() = runTest {
        val (s, c) = session(); s.restore(); assertTrue(s.state.value is SessionState.SignedIn<*>)
        meStatus = HttpStatusCode.Unauthorized                      // access token bị từ chối → thử làm mới → refresh chết → sessionExpired
        assertFailsWith<ApiError> { c.get<Me>("/api/me") }
        assertEquals(SessionState.SignedOut, s.state.value)
    }

    @Test fun getBytesSendsTheJwtAndMapsErrors() = runTest {
        var auth: String? = null
        val engine = MockEngine { req -> auth = req.headers[HttpHeaders.Authorization]; if (req.url.encodedPath.endsWith("/missing")) respond("", HttpStatusCode.NotFound) else respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }
        val c = AuthedClient(HttpTransport("https://x.test", "app", engine), MemoryTokenStore("jwt1", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        assertEquals(listOf<Byte>(1, 2, 3), c.getBytes("/api/avatar").toList()); assertEquals("Bearer jwt1", auth)
        assertTrue(assertFailsWith<ApiError> { c.getBytes("/api/missing") }.isNotFound)
    }
}

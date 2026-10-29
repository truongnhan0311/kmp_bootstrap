package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable data class UserProfile(val name: String, val email: String)

/** VÍ DỤ CHẠY ĐƯỢC của Session (server giả). Xem ghi chú ở CoreExamples. */
class SessionExamples {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun sessionLifecycle() = runBlocking {
        val server = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/login") -> respond("""{"access_token":"jwt","refresh_token":"ref"}""", HttpStatusCode.OK, json)
                req.url.encodedPath.endsWith("/me") -> respond("""{"name":"Nhan","email":"nhan@example.com"}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.OK, json)
            }
        }
        val client = AuthedClient("https://api.example.com", MemoryTokenStore(), PlainJsonContract(Endpoints.taper()), "my-app", server)
        val prefs = MemoryKeyValueStore()
        // region: session-setup
        val session = Session(client, loadUser = { client.get<UserProfile>("/api/v2/auth/me") },
            userSerializer = UserProfile.serializer(), prefs = prefs)        // có serializer + prefs: mở app lúc mất mạng vẫn vào được bằng người dùng đã lưu
        session.onSignedIn = { user -> AppLog.i("session", "signed in as ${user.email}") }   // đồng bộ cài đặt, đăng ký thiết bị nhận thông báo…
        session.restore()                                                // lúc mở app: Loading → SignedOut hoặc SignedIn(user)
        // trong Compose:  val state by session.state.collectAsState()
        //   Loading → màn chờ · SignedOut → màn đăng nhập · SignedIn(user) → app
        // endregion
        // region: session-login
        val response = client.raw("POST", Endpoints.taper().login, buildJsonObject { put("email", "nhan@example.com"); put("password", "secret") }, auth = false)
        val ok = session.signIn(response)                                // lưu token rồi tải người dùng; false nếu sai mật khẩu (không ném lỗi)
        if (!ok) println(client.errorMessageOf(response))                // câu báo lỗi cho người dùng: lời của server, hoặc câu mặc định theo ngôn ngữ
        // endregion
        assertTrue(ok); assertEquals("Nhan", session.user?.name)
        // region: session-logout
        session.signOut(beforeLogout = { /* việc dọn khi còn token: push.unregister() … */ })   // lỗi trong việc dọn không chặn đăng xuất
        // endregion
        assertEquals(SessionState.SignedOut, session.state.value)
    }
}

package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

@Serializable data class User(val id: Int, val name: String)
@Serializable data class NewUser(val name: String)

/** VÍ DỤ CHẠY ĐƯỢC của appkit-network (server giả bằng MockEngine). Xem ghi chú ở CoreExamples. */
class NetworkExamples {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val server = MockEngine { req ->
        when {
            req.url.encodedPath.endsWith("/auth/login") -> respond("""{"access_token":"jwt-1","refresh_token":"refresh-1"}""", HttpStatusCode.OK, json)
            req.url.encodedPath.endsWith("/users/404") -> respond("""{"message":"no such user"}""", HttpStatusCode.NotFound, json)
            req.method.value == "POST" -> respond("""{"id":7,"name":"Nhan"}""", HttpStatusCode.Created, json)
            else -> respond("""{"id":1,"name":"Nhan"}""", HttpStatusCode.OK, json)
        }
    }

    @Test fun clientBasics() { runBlocking {
        val appScope = CoroutineScope(Dispatchers.Default)   // trong app thật: scope của Application/ViewModel
        System.setProperty("appkit.storage.dir", java.nio.file.Files.createTempDirectory("ex").toString())
        // region: network-setup
        // MỘT chỗ khai báo đường dẫn; đổi phiên bản API hay backend chỉ sửa ở đây
        val endpoints = Endpoints.taper("/api/v2", extra = mapOf("users" to "/api/v2/users"))
        AppKit.start(AppKitConfig(
            name = "my-app", baseUrls = listOf("https://api.example.com"),     // nhiều địa chỉ = tự chuyển sang địa chỉ dự phòng
            contract = PlainJsonContract(endpoints),                           // EnvelopeContract(...) cho backend {code,message,data}
            engine = server,                                                   // bỏ dòng này ở app thật
        ))
        // endregion
        // region: network-login
        val r = AppKit.client.raw("POST", AppKit.endpoints.login, buildJsonObject { put("email", "a@b.c"); put("password", "secret") }, auth = false)
        check(AppKit.client.storeLogin(r))            // lưu cặp token; từ giờ mọi lời gọi tự gắn JWT và tự làm mới khi hết hạn
        // endregion
        // region: network-calls
        val me: User = AppKit.client.get("/api/v2/auth/me")                              // GET → đối tượng
        val created: User = AppKit.client.post(AppKit.endpoints["users"], NewUser("Nhan")) // POST + body @Serializable
        val missing: User? = AppKit.client.getOrNull("/api/v2/users/404")                // 404 → null thay vì ném lỗi
        AppKit.client.sendNoContent("DELETE", "/api/v2/users/7")                          // không cần dữ liệu trả về
        // endregion
        assertEquals(User(1, "Nhan"), me); assertEquals(7, created.id); assertNull(missing)
        // region: network-errors
        try {
            AppKit.client.get<User>("/api/v2/users/404")
        } catch (e: ApiError) {
            when (e.kind) {
                ErrorKind.NotFound -> println("không có: ${e.message}")          // lời của server nếu có, không thì câu mặc định theo ngôn ngữ
                ErrorKind.Timeout, ErrorKind.Network -> println("mạng lỗi, thử lại sau")
                ErrorKind.Unauthorized -> println("cần đăng nhập lại")
                else -> println("lỗi ${e.status}, mã tra log: ${e.requestId}")
            }
        }
        // endregion
        // region: network-cache
        val cached: User = AppKit.client.get("/api/v2/users/1", cache = CachePolicy(1.hours))   // 1 giờ không gọi lại; offline → bản cũ
        val fresh: User = AppKit.client.get("/api/v2/users/1", cache = CachePolicy(1.hours, refresh = true)) // bỏ qua cache lần này
        // ghi (POST/PUT/PATCH/DELETE) thành công tự xoá cache của tài nguyên đó; đăng xuất xoá hết
        // endregion
        assertEquals(cached, fresh)
        // region: network-session
        val sessionJob = appScope.launch { AppKit.client.sessionExpired.collect { /* điều hướng về màn đăng nhập */ } }
        AppKit.refreshSession()                      // làm mới phiên ngay, gọi từ đâu cũng được; nhiều nơi gọi cùng lúc chỉ đổi token một lần
        AppKit.client.logout()                       // thu hồi trên server (nếu được), xoá token và cache cục bộ
        // endregion
        sessionJob.cancel(); assertTrue(!AppKit.client.hasSession()); System.clearProperty("appkit.storage.dir")
    } }
}

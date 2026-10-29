package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kmp.bootstrap.appkit.push.DevicesPushContract
import kmp.bootstrap.appkit.push.PushManager
import kmp.bootstrap.appkit.push.PushMessage
import kmp.bootstrap.appkit.push.PushNavigation
import kmp.bootstrap.appkit.push.PushTokenSource
import kmp.bootstrap.appkit.push.PushTokens
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VÍ DỤ CHẠY ĐƯỢC của appkit-push (server giả; token FCM giả). Xem ghi chú ở CoreExamples. */
class PushExamples {
    @Test fun register() = runBlocking {
        val prefs = MemoryKeyValueStore()
        val client = AuthedClient("https://api.example.com", MemoryTokenStore("jwt", "refresh"), PlainJsonContract(Endpoints.taper()), "my-app",
            MockEngine { respond("{}", HttpStatusCode.OK) })
        // region: push-register
        val push = PushManager(client, prefs, DevicesPushContract(Endpoints.taper()), appVersion = "1.0",
            tokenSource = PushTokenSource { "fcm-token" })     // app thật: bỏ tham số này, kit tự lấy token FCM của nền tảng
        val ok = push.registerIfNeeded(userKey = "user-1")      // sau đăng nhập và mỗi lần mở app; chỉ gọi server khi token/người dùng đổi
        push.unregister()                                       // TRƯỚC khi đăng xuất (sau đó không còn quyền gọi)
        // endregion
        assertTrue(ok); PushTokens.set(null)   // token là trạng thái toàn cục: dọn để test khác không bị ảnh hưởng
    }

    @Test fun navigation() {
        // region: push-navigation
        // nền tảng gọi khi người dùng bấm thông báo (Android: AppKitPush.handleIntent, iOS: AppDelegate); app quan sát và điều hướng
        PushNavigation.request(PushMessage.fromData(mapOf("type" to "activity", "uuid" to "abc")))
        val target = PushNavigation.target.value        // trong Compose: val target by PushNavigation.target.collectAsState()
        PushNavigation.consumed()                       // điều hướng xong thì xoá, để xoay màn hình không điều hướng lặp
        // endregion
        assertEquals("activity", target?.type); assertEquals("abc", target?.id)
    }
}

package kmp.bootstrap.appkit

import kotlin.test.Test
import kotlin.test.assertEquals

// region: nav-routes
sealed interface Route {                                  // route của app: sealed interface, mỗi màn một giá trị
    data object Home : Route
    data object Calendar : Route
    data class Detail(val id: String) : Route
    data class PublicActivity(val token: String) : Route
}
// endregion

/** VÍ DỤ CHẠY ĐƯỢC của điều hướng và cài đặt (appkit-core). Xem ghi chú ở CoreExamples. */
class NavigationExamples {
    @Test fun navigator() {
        // region: nav-stack
        val nav = Navigator<Route>(Route.Home)
        nav.push(Route.Detail("42"))        // vào màn con
        nav.push(Route.Calendar)
        nav.back()                          // lùi một màn → Detail("42"); trả false nếu đã ở gốc (để hệ điều hành thoát app)
        nav.reset(Route.Home)               // đổi tab gốc / sau đăng nhập: xoá cả ngăn xếp
        val where = nav.current             // Route hiện tại; nav.stack là StateFlow để UI quan sát
        // endregion
        assertEquals(Route.Home, where)
    }

    @Test fun deepLinks() {
        // region: nav-deeplinks
        val links = DeepLinks<Route> {
            route("/s/{token}") { Route.PublicActivity(it.getValue("token")) }    // {tên} là tham số của đường dẫn
            route("/activities/{id}") { Route.Detail(it.getValue("id")) }
        }
        val a = links.resolve("https://app.example.com/s/abc123?utm=x")    // → PublicActivity("abc123"): bỏ scheme, tên miền, query
        val b = links.resolve("myapp://activities/42")                      // → Detail("42")
        val c = links.resolve("https://app.example.com/unknown")            // → null: không khớp mẫu nào
        // endregion
        assertEquals(Route.PublicActivity("abc123"), a); assertEquals(Route.Detail("42"), b); assertEquals(null, c)
    }

    @Test fun settings() {
        // region: settings-persist
        val settings = AppSettings(MemoryKeyValueStore())     // app thật: AppSettings(AppKit.storage.prefs)
        settings.setTheme(ThemeMode.Dark)                      // lưu và phát cho UI (settings.theme là StateFlow<ThemeMode>)
        settings.setLanguage("vi")                             // lưu và đổi Language.current; "" = quay về theo máy
        // sau đăng nhập: lựa chọn đã lưu ở tài khoản thắng máy, TRỪ trường máy vừa đổi mà chưa đẩy lên (lựa chọn gần nhất của người dùng thắng)
        val deviceWins = settings.applyAccount(theme = "light", language = "en")
        if (deviceWins.isNotEmpty()) settings.markSynced()     // … sau khi app đẩy các lựa chọn của máy lên tài khoản
        // endregion
        assertEquals(setOf("theme", "language"), deviceWins); Language.reset()
    }
}

# Điều hướng

Hai phần, tách nhau để test được: **`Navigator`** (ngăn xếp, ở `appkit-core`, không dính Compose) và **`NavHost`** (nối vào màn hình, ở `appkit-ui`).
Route của app là một `sealed interface` bất kỳ — mỗi màn một giá trị, tham số đi theo giá trị (`Detail(id)`).

<!-- snippet: nav-routes -->
```kotlin
sealed interface Route {                                  // route của app: sealed interface, mỗi màn một giá trị
    data object Home : Route
    data object Calendar : Route
    data class Detail(val id: String) : Route
    data class PublicActivity(val token: String) : Route
}
```
<!-- /snippet -->

## Ngăn xếp

`push` vào màn con (bấm đúp không mở hai lần), `back()` lùi (trả `false` ở gốc để hệ điều hành thoát app), `reset` đổi tab gốc hoặc sau đăng nhập (xoá cả ngăn xếp),
`replace` thay màn hiện tại, `popTo { … }` lùi về route gần nhất thoả điều kiện. `nav.stack` là `StateFlow` để UI quan sát.

<!-- snippet: nav-stack -->
```kotlin
val nav = Navigator<Route>(Route.Home)
nav.push(Route.Detail("42"))        // vào màn con
nav.push(Route.Calendar)
nav.back()                          // lùi một màn → Detail("42"); trả false nếu đã ở gốc (để hệ điều hành thoát app)
nav.reset(Route.Home)               // đổi tab gốc / sau đăng nhập: xoá cả ngăn xếp
val where = nav.current             // Route hiện tại; nav.stack là StateFlow để UI quan sát
```
<!-- /snippet -->

## Nối vào màn hình

`NavHost` vẽ route hiện tại và bắt nút lùi hệ điều hành (Android: lùi ngăn xếp; iOS và desktop: không có nút lùi hệ thống, dùng nút ‹ của `AppHeader(onBack = …)`).

<!-- snippet: ui-navhost -->
```kotlin
sealed interface AppRoute { data object Home : AppRoute; data class Detail(val id: String) : AppRoute }

@Composable
fun AppNavigation(nav: Navigator<AppRoute>) {
    NavHost(nav) { route ->                              // vẽ route hiện tại; nút lùi Android tự lùi ngăn xếp
        when (route) {
            AppRoute.Home -> Page { ListBlock("Buổi tập", items = listOf(ListRow("Đạp sáng", onClick = { nav.push(AppRoute.Detail("1")) }))) }
            is AppRoute.Detail -> AppScaffold(topBar = { AppHeader("Chi tiết", onBack = { nav.back() }) }) { Page { Txt("Buổi ${route.id}") } }
        }
    }
}
```
<!-- /snippet -->

## Deep link và link chia sẻ

Mẫu đường dẫn có tham số `{tên}`, khớp theo từng đoạn. Link web (`https://tên-miền/…`) bỏ tên miền; link scheme riêng (`myapp://s/abc`) coi `s/abc` là đường dẫn.
Query và `#` bị bỏ qua; không khớp mẫu nào thì `null` (app mở màn mặc định). Một mẫu có thể từ chối (`return null`) để mẫu sau được thử.

<!-- snippet: nav-deeplinks -->
```kotlin
val links = DeepLinks<Route> {
    route("/s/{token}") { Route.PublicActivity(it.getValue("token")) }    // {tên} là tham số của đường dẫn
    route("/activities/{id}") { Route.Detail(it.getValue("id")) }
}
val a = links.resolve("https://app.example.com/s/abc123?utm=x")    // → PublicActivity("abc123"): bỏ scheme, tên miền, query
val b = links.resolve("myapp://activities/42")                      // → Detail("42")
val c = links.resolve("https://app.example.com/unknown")            // → null: không khớp mẫu nào
```
<!-- /snippet -->

Nối vào hệ điều hành: Android bắt `intent.data` ở `MainActivity`, iOS bắt `onOpenURL`; cả hai chuyển chuỗi link vào `links.resolve(…)` rồi `nav.push/reset`.
Khai báo scheme/host trong manifest (Android) và `CFBundleURLTypes`/Associated Domains (iOS) là việc của app.

**Đã kiểm (JVM):** ngăn xếp, deep link (kể cả link scheme riêng, đoạn thừa, mẫu từ chối). **Chưa kiểm:** `BackHandler` thật trên Android.

# Phiên người dùng và cài đặt

## Session — "đang kiểm tra / chưa đăng nhập / đã đăng nhập"

`Session<U>` (ở `appkit-network`) giữ **trạng thái** để màn hình quan sát; mọi việc token đã do `AuthedClient` lo. `U` là kiểu người dùng của app.
Hết phiên thật (refresh token chết) ở bất kỳ đâu trong app → tự về `SignedOut`. Mở app khi **mất mạng** mà đã có phiên → vào app bằng người dùng đã lưu lần trước
(cần `userSerializer` + `prefs`), không bị đá ra màn đăng nhập oan. Mở app mà server trả 401 → xoá token, về đăng nhập.

<!-- snippet: session-setup -->
```kotlin
val session = Session(client, loadUser = { client.get<UserProfile>("/api/v2/auth/me") },
    userSerializer = UserProfile.serializer(), prefs = prefs)        // có serializer + prefs: mở app lúc mất mạng vẫn vào được bằng người dùng đã lưu
session.onSignedIn = { user -> AppLog.i("session", "signed in as ${user.email}") }   // đồng bộ cài đặt, đăng ký thiết bị nhận thông báo…
session.restore()                                                // lúc mở app: Loading → SignedOut hoặc SignedIn(user)
// trong Compose:  val state by session.state.collectAsState()
//   Loading → màn chờ · SignedOut → màn đăng nhập · SignedIn(user) → app
```
<!-- /snippet -->

### Đăng nhập

`signIn` trả `false` (không ném lỗi) khi phản hồi không có token — sai mật khẩu, tài khoản khoá… — để màn đăng nhập hiện câu báo lỗi.

<!-- snippet: session-login -->
```kotlin
val response = client.raw("POST", Endpoints.taper().login, buildJsonObject { put("email", "nhan@example.com"); put("password", "secret") }, auth = false)
val ok = session.signIn(response)                                // lưu token rồi tải người dùng; false nếu sai mật khẩu (không ném lỗi)
if (!ok) println(client.errorMessageOf(response))                // câu báo lỗi cho người dùng: lời của server, hoặc câu mặc định theo ngôn ngữ
```
<!-- /snippet -->

### Đăng xuất

`beforeLogout` chạy **khi còn token** (gỡ thiết bị nhận thông báo, xoá dữ liệu cục bộ); lỗi trong đó chỉ ghi log, không chặn đăng xuất. Sau đó thu hồi phiên trên máy chủ,
xoá token + cache + người dùng đã lưu.

<!-- snippet: session-logout -->
```kotlin
session.signOut(beforeLogout = { /* việc dọn khi còn token: push.unregister() … */ })   // lỗi trong việc dọn không chặn đăng xuất
```
<!-- /snippet -->

## AppSettings — giao diện và ngôn ngữ được nhớ

`AppSettings` (ở `appkit-core`) nhớ giao diện sáng/tối/theo máy và ngôn ngữ qua các lần mở app, và đặt `Language.current` ngay khi tạo.
Quy tắc **"lựa chọn gần nhất của người dùng thắng"**: chọn trên máy mà chưa đẩy lên được tài khoản thì lần đăng nhập sau **không bị tài khoản ghi đè**
(đây là lỗi thật Taper từng gặp: chọn English mà cứ tự về tiếng Việt).

<!-- snippet: settings-persist -->
```kotlin
val settings = AppSettings(MemoryKeyValueStore())     // app thật: AppSettings(AppKit.storage.prefs)
settings.setTheme(ThemeMode.Dark)                      // lưu và phát cho UI (settings.theme là StateFlow<ThemeMode>)
settings.setLanguage("vi")                             // lưu và đổi Language.current; "" = quay về theo máy
// sau đăng nhập: lựa chọn đã lưu ở tài khoản thắng máy, TRỪ trường máy vừa đổi mà chưa đẩy lên (lựa chọn gần nhất của người dùng thắng)
val deviceWins = settings.applyAccount(theme = "light", language = "en")
if (deviceWins.isNotEmpty()) settings.markSynced()     // … sau khi app đẩy các lựa chọn của máy lên tài khoản
```
<!-- /snippet -->

Dùng trong giao diện: `AppTheme(light, dark, settings = settings) { … }` — `settings.setTheme(…)` ở đâu cũng được, UI đổi ngay.

<!-- snippet: ui-theme-settings -->
```kotlin
@Composable
fun ThemedApp(light: AppColors, dark: AppColors, settings: kmp.bootstrap.appkit.AppSettings) {
    AppTheme(light = light, dark = dark, settings = settings) {      // settings.setTheme(ThemeMode.Dark) ở đâu cũng được → UI đổi ngay và được nhớ
        Page { Segmented(listOf(ThemeMode.System to "Theo máy", ThemeMode.Light to "Sáng", ThemeMode.Dark to "Tối"), settings.theme.value, { settings.setTheme(it) }) }
    }
}
```
<!-- /snippet -->

### Ảnh đại diện từ mạng

`AsyncAvatar` hiện chữ cái đầu lúc chờ và khi tải hỏng; ảnh đã giải mã được nhớ theo URL (`ImageCache`, tối đa 100 ảnh, 1 giờ). `fetch` là cách tải byte — với `appkit-network` là `{ client.getBytes(it) }` (tự gắn JWT).

<!-- snippet: ui-avatar -->
```kotlin
@Composable
fun ProfileHeader(fetch: suspend (String) -> ByteArray?, name: String, avatarUrl: String?) {
    // fetch là cách TẢI byte; với appkit-network: ProfileHeader({ client.getBytes(it) }, …) — tự gắn JWT, tự làm mới phiên
    AsyncAvatar(name, avatarUrl, fetch = fetch, size = 56)      // chữ cái đầu lúc chờ/lỗi; ảnh đã giải mã được nhớ theo URL
}
```
<!-- /snippet -->

**Đã kiểm (JVM):** vòng đời phiên (không có phiên, khôi phục, 401, mất mạng có/không người dùng đã lưu, đăng nhập thất bại, đăng xuất dọn trước, dọn lỗi, hết phiên ở nơi khác),
`AppSettings` (nhớ qua khởi động lại, rỗng = theo máy, tài khoản vs máy, dữ liệu rác), `getBytes`. **Chưa kiểm:** `AsyncAvatar`/`decodeImage` thật (cần Compose runtime).

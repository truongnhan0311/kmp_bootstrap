# kmp_bootstrap

Bộ khung dùng chung cho app Kotlin Multiplatform (Android + iOS): **đủ đồ, cần gì lấy nấy** — giống cách Goravel cho backend.
> Đây là **SDK nội bộ**. Agent/người mới: đọc [AGENTS.md](AGENTS.md) (pull về và kiểm tra trong 1 lệnh) rồi `docs/<module>.md` — mỗi mục có ví dụ chạy được.

Mỗi mảnh là một module riêng; app chỉ khai module mình dùng, các module khác không bị kéo vào.

> **Bắt đầu một app mới:** `node tools/new-app.mjs --name my-app --id com.acme.myapp --out ../my-app --modules network,push,platform,ui` — xem [docs/new-app.md](docs/new-app.md).

## Chọn module

| Module | Có gì | Kéo theo |
|---|---|---|
| `appkit-core` | `Language` (chữ theo ngôn ngữ), `Navigator` + `DeepLinks` (điều hướng), `AppSettings` (giao diện + ngôn ngữ được nhớ), `SvgParser` (đọc SVG), `AppLog` (log xoay vòng, che bí mật), `AppStorage` (lưu cục bộ + két an toàn), `AppCrypto` (AES-256-GCM, SHA-256), `TtlCache` (cache có hạn), `Jwt` | coroutines, serialization, datetime, okio, cryptography |
| `appkit-network` | `Session` (đang kiểm tra/đăng nhập/đăng xuất), `AuthedClient` (JWT tự gắn + tự làm mới phiên), `HttpTransport` (failover, timeout, thử lại GET), `get/post/put/patch/delete`, `ApiError` có phân loại, cache GET, `Endpoints` (một chỗ khai báo đường dẫn), `AppKit.start()` | core + Ktor |
| `appkit-push` | thông báo đẩy FCM: đăng ký thiết bị, điều hướng khi bấm | network (+ Firebase trên Android) |
| `appkit-platform` | mở link, copy, chia sẻ, chọn ảnh, múi giờ qua một interface (có bản giả để test) | core |
| `appkit-reminder` | nhắc giờ bằng thông báo cục bộ (chạy cả khi app tắt), nhắc lặp đúng giờ qua giờ mùa hè | core |
| `appkit-ui` | component Material 3 kiểu shadcn, theme sinh từ CSS của web, layout kiểu Tailwind, block kiểu Bootstrap, icon SVG, app mẫu 5 tab | core + Compose |

Chỉ cần mã hoá và log? Lấy `appkit-core` — không có Ktor, không có Firebase (đã kiểm: `samples/core-only`).

## Cài đặt

Chưa phát hành lên Maven; dùng như thư viện nguồn bằng `includeBuild` (đặt repo cạnh app, hoặc thêm làm git submodule):

```kotlin
// settings.gradle.kts của app
includeBuild("../kmp_bootstrap")

// build.gradle.kts của module dùng chung (commonMain)
commonMain.dependencies {
    implementation("kmp.bootstrap:appkit-network")     // kéo cả appkit-core
    implementation("kmp.bootstrap:appkit-push")        // tuỳ chọn
}
```

Android: gọi `AppKitAndroid.init(context)` một lần ở `Application.onCreate`. iOS: không cần gì thêm (push: xem `docs/push.md`).
Máy không có Android SDK / Xcode (CI, sandbox): thêm `-PdesktopOnly=true` để chỉ dựng và test bản JVM.

## Dùng

```kotlin
// một lần, lúc khởi động
AppKit.start(AppKitConfig(name = "my-app", baseUrls = listOf("https://api.example.com"), contract = PlainJsonContract(Endpoints.taper("/api/v2"))))

// ở BẤT KỲ ĐÂU: JWT tự gắn, tự làm mới, body @Serializable, lỗi là ApiError có kind
val me: User = AppKit.client.get("/api/v2/auth/me")
val feed: Feed = AppKit.client.get("/api/v2/feed", cache = CachePolicy.hours(1))   // cache 1 giờ; mất mạng thì trả bản cũ
val item: Item = AppKit.client.post("/api/v2/items", NewItem("x"))                  // put / patch tương tự
val maybe: Item? = AppKit.client.getOrNull("/api/v2/items/9")                        // 404 → null
AppKit.client.delete("/api/v2/items/9")
AppKit.refreshSession()                                                              // làm mới phiên ngay
AppKit.storage.prefs.putBool("dark", true)                                           // lưu cục bộ; AppKit.storage.tokens = token an toàn
AppLog.i("home", "opened")                                                           // console + file tự xoay vòng
val box = AppCrypto.encryptText("bí mật", "mật khẩu")                                // AES-256-GCM
val profiles = TtlCache<String, Profile>(1.hours)                                    // cache dùng độc lập, không dính HTTP
profiles.getOrLoad(userId) { api.profile(userId) }                                   // N nơi gọi cùng lúc chỉ tải một lần
AppKit.client.sessionExpired.collect { goToLogin() }                                 // refresh token chết thật
```

Đăng nhập: `client.raw("POST", AppKit.endpoints.login, body, auth = false)` rồi `client.storeLogin(r)`.

## Nó lo giúp bạn điều gì

| Việc | Cách |
|---|---|
| Gắn `Authorization: Bearer` và `X-App-Id` | tự động |
| Access token sắp hết hạn (< 2 phút, đọc `exp` của JWT) | làm mới **trước** khi gửi |
| Server trả "token hết hạn/bị thu hồi" | làm mới rồi **gửi lại đúng 1 lần** |
| 8 request 401 cùng lúc | chỉ **một** lần đổi refresh token (refresh dùng một lần; đổi nhiều lần = bị coi là bị đánh cắp) |
| Mạng hỏng đúng lúc làm mới | giữ token, lần sau thử lại (không đăng xuất oan) |
| Refresh token chết thật | xoá token + xoá cache + phát `sessionExpired` |
| Lỗi tạm khi ĐỌC (timeout, 5xx, 429) | GET tự thử lại (mặc định 1 lần); request GHI không bao giờ tự thử lại (tránh ghi trùng) |
| Mọi thất bại | một `ApiError` có `kind` (Network, Timeout, Unauthorized, NotFound, Invalid, Server, Parse…), một dòng log kèm `requestId` |
| Chữ báo lỗi cho người dùng | theo ngôn ngữ: app đặt → hệ thống → EN (en/vi/zh/es); lời của server giữ nguyên |
| Tên miền chính sập (DNS) | `Failover(listOf(chính, dựphòng))` thử tên kế tiếp |
| Cache GET (bật theo từng lời gọi) | TTL, bản cũ khi lỗi, ngăn riêng từng người dùng, tự xoá khi ghi, xoá sạch khi đăng xuất |
| Log | file xoay vòng + xoá theo tuổi; mật khẩu che hẳn, token giữ 5 ký tự cuối để tra |

Hai kiểu backend, một cơ chế: chỗ **duy nhất** khác nhau là `AuthContract` (`PlainJsonContract` kiểu Taper, `EnvelopeContract` kiểu khung `{code,message,data}`) cùng `Endpoints` (đường dẫn). Backend thứ ba: cài `AuthContract` (6 hàm nhỏ).

## Cấu trúc

```
appkit-core/      Language  Log  Storage  Stores  Crypto  TtlCache  Jwt
appkit-network/   HttpTransport  AuthedClient  Requests  Endpoints  AuthContract  Failover  ApiError  Cache  AppKit
appkit-push/      Push  PushNavigation                       → docs/push.md
appkit-platform/  PlatformServices (openUrl, clipboard, share, pickImage, timezone) + Fake  → docs/platform.md
appkit-reminder/  Reminders  (Android AlarmManager, iOS UNUserNotificationCenter)  → docs/reminder.md
appkit-ui/        Theme  Components
  mỗi module: src/{commonMain,commonTest,desktopMain,androidMain,iosMain}  (expect/actual cho phần theo nền tảng)
tools/shadcn-theme.mjs   sinh AppColors sáng/tối từ CSS shadcn (node --test tools/*.test.mjs)
samples/core-only/       app mẫu chỉ lấy appkit-core
tools/new-app.mjs        sinh dự án app mới (Android + iOS + desktop) nối sẵn kit
docs/*.md                tài liệu từng module, mỗi mục có ví dụ CHÉP TỪ CODE ĐÃ CHẠY (node tools/sync-snippets.mjs)
AGENTS.md                hướng dẫn cho agent: pull về, dùng, sửa
CHANGELOG.md
```

## Chạy test

```sh
./gradlew -PdesktopOnly=true desktopTest     # JVM (riêng :appkit-ui chỉ biên dịch được: compileTestKotlinDesktop) — không cần Android SDK/Xcode
./gradlew build                              # máy có Android Studio/Xcode: đủ Android + iOS
node --test tools/*.test.mjs                           # bộ sinh theme
```

Đã kiểm trên JVM: 126 test (core 60, network 51, push 10, reminder 10, platform 5) + 9 test công cụ (bộ sinh theme, SVG → Kotlin, sinh app); dự án sinh bởi new-app biên dịch được với kit; ví dụ trong docs là code đã biên dịch và chạy.
**Chưa kiểm** (cần Android Studio/Xcode/thiết bị): mọi file `androidMain`/`iosMain` (Keystore, Keychain, logcat, FCM, AlarmManager, UNUserNotificationCenter, Swift), build Android/iOS;
`appkit-ui` **biên dịch được** nhưng chưa chạy lúc runtime/chưa chụp màn hình (môi trường dựng không tải được `androidx.collection`); backend thật.

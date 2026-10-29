# kmp_bootstrap

Phần **bắt buộc giống nhau** của mọi app Kotlin Multiplatform (Android + iOS) của mình, tách ra một lần để khỏi chép lại
giữa Taper, qt-depot và các app sau: gọi API có token, tự làm mới phiên, báo hết phiên, tên miền dự phòng.

Không UI, không phụ thuộc backend cụ thể. Chỉ thư viện chuẩn của hệ sinh thái Kotlin (Ktor, kotlinx).

## Dùng

```kotlin
// một lần, lúc khởi động (Android: AppKitAndroid.init(context) trước đó)
AppKit.start(AppKitConfig(name = "my-app", baseUrls = listOf("https://api.example.com"), contract = PlainJsonContract()))

// ở BẤT KỲ ĐÂU: JWT tự gắn, tự làm mới, body @Serializable, lỗi là ApiError có kind
val me: User = AppKit.client.get("/api/auth/me")
val item: Item = AppKit.client.post("/api/items", NewItem("x"))      // put / patch tương tự
val maybe: Item? = AppKit.client.getOrNull("/api/items/9")            // 404 → null
AppKit.client.delete("/api/items/9")
AppKit.refreshSession()                                               // làm mới phiên ngay, gọi từ đâu cũng được
AppKit.storage.prefs.putBool("dark", true)                            // lưu cục bộ;  AppKit.storage.tokens = token an toàn
AppLog.i("home", "đã mở")                                             // log ra console + file tự xoay vòng
val box = AppCrypto.encryptText("bí mật", "mật khẩu")                 // AES-256-GCM
client.sessionExpired.collect { goToLogin() }                         // refresh token chết thật
```

Đăng nhập: `client.raw("POST", "/api/auth/login", body, auth = false)` rồi `client.storeLogin(r)`.

## Các lớp (ghép, không kế thừa)

```
HttpTransport    gửi request: failover, timeout, thử lại GET, log, phân loại lỗi mạng/timeout      (không biết đăng nhập)
  └─ AuthedClient   bọc transport: JWT, làm mới trước hạn, 401→làm mới→gửi lại, sessionExpired
       └─ get/post/put/patch/delete, getOrNull   (Requests.kt)  body @Serializable → JSON, JSON → đối tượng, lỗi → ApiError(kind)
AppStorage       prefs (SharedPreferences/NSUserDefaults/file) + secrets (Keystore/Keychain) + tokens
Strings          chữ hiện cho người dùng (lỗi…) theo ngôn ngữ: app đặt → ngôn ngữ hệ thống → EN; en/vi/zh/es, thêm bằng Strings.register
AppLog           console + RotatingFileSink (xoay vòng, xoá theo tuổi, che mật khẩu hẳn; token giữ 5 ký tự cuối để tra log)
AppCrypto        mã hoá/giải mã AES-256-GCM, khoá từ mật khẩu (PBKDF2) hoặc khoá thô, SHA-256
push/            PushManager + PushContract (Taper / qt-depot) + PushNavigation   → docs/push.md
ui/ (module)     component Material 3 + theme sinh từ CSS shadcn (tools/shadcn-theme.mjs)
```

## Nó lo giúp bạn điều gì

| Việc | Cách |
|---|---|
| Gắn `Authorization: Bearer` và `X-App-Id` | tự động |
| Access token sắp hết hạn (< 2 phút, đọc `exp` của JWT) | làm mới **trước** khi gửi |
| Server trả "token hết hạn/bị thu hồi" | làm mới rồi **gửi lại đúng 1 lần**, người dùng không thấy lỗi |
| 8 request 401 cùng lúc | chỉ **một** lần đổi refresh token (refresh dùng một lần; đổi nhiều lần = bị coi là bị đánh cắp và thu hồi cả phiên) |
| Mạng hỏng đúng lúc làm mới | giữ token, lần sau thử lại (không đăng xuất oan) |
| Refresh token chết thật | xoá token + phát `sessionExpired` |
| Lỗi tạm khi ĐỌC (timeout, 5xx, 429) | GET tự thử lại (mặc định 1 lần); request GHI không bao giờ tự thử lại — tránh ghi trùng |
| Mọi thất bại | một `ApiError` có `kind`: Network, Timeout, Unauthorized, Forbidden, NotFound, Invalid, RateLimited, Server, Parse |
| Tên miền chính sập (DNS) | `Failover(listOf(chính, dựphòng))` thử tên kế tiếp, nhớ tên chạy được |
| Đăng xuất | `logout()` thu hồi trên server (best-effort) rồi xoá token cục bộ |

## Hai kiểu backend, một cơ chế: `AuthContract`

Chỗ **duy nhất** các backend khác nhau là hợp đồng xác thực. Có sẵn hai bản:

- `PlainJsonContract` — Taper: JSON phẳng `{access_token, refresh_token}`, lỗi là HTTP 401.
- `EnvelopeContract` — qt-depot: khung `{code, message, data}`, token hết hạn có thể nằm trong HTTP 200 (`Token_Revoked`).

Backend thứ ba: cài `AuthContract` (6 hàm nhỏ) — cơ chế single-flight/thử lại/báo hết phiên không phải viết lại.

## Tệp

```
kit/src/commonMain/kotlin/kmp/bootstrap/appkit/
  HttpTransport.kt  AuthedClient.kt  Requests.kt  AuthContract.kt  Failover.kt  ApiError.kt
  Storage.kt  Stores.kt  Log.kt  Crypto.kt  Jwt.kt  AppKit.kt  push/
kit/src/{desktop,android,ios}Main/…   phần theo nền tảng (expect/actual)
ui/                                    component Material 3 + theme
tools/shadcn-theme.mjs                 sinh AppColors sáng/tối từ CSS shadcn (có test: node --test tools/)
docs/push.md                           nối FCM cho Android + iOS (kèm mẫu AppDelegate.swift)
```

## Chạy test / build

```sh
./gradlew -PdesktopOnly=true :kit:desktopTest     # ở máy không có Android SDK (CI, sandbox)
./gradlew :kit:build                              # máy có Android Studio/Xcode: đủ Android + iOS
```

Đã kiểm trên JVM: 53 test (MockEngine, FakeFileSystem, file tạm) — làm mới single-flight, request/lỗi/JSON/404/timeout/thử lại GET, body @Serializable + JWT,
lưu trữ bền qua lần mở lại, log xoay vòng + che bí mật, mã hoá (sai khoá/bị sửa đều thất bại, SHA-256 đúng vector chuẩn), push, và bộ sinh theme (oklch→hex đúng).

**Chưa kiểm** (cần Android Studio/Xcode/thiết bị): mọi file `androidMain`/`iosMain` (Keystore, Keychain, logcat, FCM, Swift), build Android/iOS,
và module `ui`: **biên dịch được** (main + test) nhưng chưa chạy lúc runtime/chưa chụp màn hình (môi trường dựng không tải được `androidx.collection`). Backend thật cũng chưa thử.

# kmp_bootstrap

Phần **bắt buộc giống nhau** của mọi app Kotlin Multiplatform (Android + iOS) của mình, tách ra một lần để khỏi chép lại
giữa Taper, qt-depot và các app sau: gọi API có token, tự làm mới phiên, báo hết phiên, tên miền dự phòng.

Không UI, không phụ thuộc backend cụ thể. Chỉ thư viện chuẩn của hệ sinh thái Kotlin (Ktor, kotlinx).

## Dùng trong 10 dòng

```kotlin
val client = AuthedClient(
    baseUrl = "https://api.example.com",
    tokens = MyTokenStore(),                  // Android: EncryptedSharedPreferences · iOS: Keychain
    contract = PlainJsonContract(),           // hoặc EnvelopeContract() — xem dưới
    appId = "my-app",                         // gửi trong header X-App-Id
)
val r = client.raw("POST", "/api/auth/login", body = buildJsonObject { put("email", e); put("password", p) }, auth = false)
if (!client.storeLogin(r)) showError(r)       // lưu cặp token
val me = client.send("GET", "/api/auth/me") { json.decodeFromString<User>(it) }

launch { client.sessionExpired.collect { goToLogin() } }   // refresh token chết thật
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
| Tên miền chính sập (DNS) | `Failover(listOf(chính, dựphòng))` thử tên kế tiếp, nhớ tên chạy được |
| Đăng xuất | `logout()` thu hồi trên server (best-effort) rồi xoá token cục bộ |

## Hai kiểu backend, một cơ chế: `AuthContract`

Chỗ **duy nhất** các backend khác nhau là hợp đồng xác thực. Có sẵn hai bản:

- `PlainJsonContract` — Taper: JSON phẳng `{access_token, refresh_token}`, lỗi là HTTP 401.
- `EnvelopeContract` — qt-depot: khung `{code, message, data}`, token hết hạn có thể nằm trong HTTP 200 (`Token_Revoked`).

Backend thứ ba: cài `AuthContract` (6 hàm nhỏ) — cơ chế single-flight/thử lại/báo hết phiên không phải viết lại.

## Cấu trúc

```
kit/src/commonMain/kotlin/kmp/bootstrap/appkit/
  AuthedClient.kt   cơ chế: Bearer, làm mới, thử lại, failover, sự kiện phiên
  AuthContract.kt   khác biệt giữa các backend (Plain / Envelope)
  Failover.kt       tên miền dự phòng
  Stores.kt         TokenStore / KeyValueStore (+ bản bộ nhớ cho test)
  Jwt.kt            đọc `exp`
  ApiError.kt
```

## Chạy test / build

```sh
./gradlew -PdesktopOnly=true :kit:desktopTest     # ở máy không có Android SDK (CI, sandbox)
./gradlew :kit:build                              # máy có Android Studio/Xcode: đủ Android + iOS
```

Đã kiểm: 10 test (MockEngine) — single-flight, refresh chết, mạng hỏng, làm mới trước hạn, đường auth không tự làm mới,
Envelope trong HTTP 200, failover, logout offline. **Chưa kiểm:** build Android/iOS thật, backend thật.

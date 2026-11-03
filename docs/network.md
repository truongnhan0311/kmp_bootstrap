# appkit-network

Gọi API: JWT tự gắn và tự làm mới phiên, thử lại, tên miền dự phòng, lỗi có phân loại, cache GET. Cần `appkit-core` (kéo theo tự động) và Ktor.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-network") }
```

## Khởi động

Đường dẫn khai báo **một chỗ** (`Endpoints`); contract/push không tự mang đường dẫn. Hai backend khác nhau chỉ khác `AuthContract`
(`PlainJsonContract`: JSON phẳng, lỗi là HTTP 4xx — Taper; `EnvelopeContract`: khung `{code,message,data}` — qt-depot) và `Endpoints`.
Backend thứ ba: cài `AuthContract` (6 hàm nhỏ).

<!-- snippet: network-setup -->
```kotlin
// MỘT chỗ khai báo đường dẫn; đổi phiên bản API hay backend chỉ sửa ở đây
val endpoints = Endpoints.taper("/api/v2", extra = mapOf("users" to "/api/v2/users"))
AppKit.start(AppKitConfig(
    name = "my-app", baseUrls = listOf("https://api.example.com"),     // nhiều địa chỉ = tự chuyển sang địa chỉ dự phòng
    contract = PlainJsonContract(endpoints),                           // EnvelopeContract(...) cho backend {code,message,data}
    engine = server,                                                   // bỏ dòng này ở app thật
))
```
<!-- /snippet -->

## Đăng nhập

Đăng nhập/đăng ký/làm mới **không** tự gắn token và không tự làm mới (khớp chính xác với `Endpoints`, không đoán theo tên đường dẫn). **Đăng xuất thì có gắn Bearer**: server thường bắt xác thực để thu hồi phiên (Taper chặn access token hiện tại).

<!-- snippet: network-login -->
```kotlin
val r = AppKit.client.raw("POST", AppKit.endpoints.login, buildJsonObject { put("email", "a@b.c"); put("password", "secret") }, auth = false)
check(AppKit.client.storeLogin(r))            // lưu cặp token; từ giờ mọi lời gọi tự gắn JWT và tự làm mới khi hết hạn
```
<!-- /snippet -->

## Gọi API

JWT tự gắn; hết hạn thì làm mới rồi gửi lại **đúng một lần**; 8 request lỗi 401 cùng lúc chỉ đổi refresh token một lần (refresh token dùng một lần,
đổi nhiều lần bị server coi là bị đánh cắp). Request đọc (GET) gặp timeout/5xx/429 tự thử lại; request ghi **không bao giờ** tự thử lại (tránh ghi trùng).

<!-- snippet: network-calls -->
```kotlin
val me: User = AppKit.client.get("/api/v2/auth/me")                              // GET → đối tượng
val created: User = AppKit.client.post(AppKit.endpoints["users"], NewUser("Nhan")) // POST + body @Serializable
val missing: User? = AppKit.client.getOrNull("/api/v2/users/404")                // 404 → null thay vì ném lỗi
AppKit.client.sendNoContent("DELETE", "/api/v2/users/7")                          // không cần dữ liệu trả về
```
<!-- /snippet -->

## Tải tệp và gửi form (có header)

`getBytes` chỉ trả byte. Khi cần tên tệp / loại tệp từ header (xuất dữ liệu, tải về) dùng `download`; gửi `multipart/form-data` (tải ảnh lên) dùng `upload`. Cả hai gắn JWT và,
khi bị từ chối, làm mới phiên rồi gửi lại **đúng một lần** (cùng khoá single-flight với `get/post`). Mã HTTP lỗi **không ném** (như `raw`):
thân lỗi của server là JSON, đọc câu báo lỗi bằng `errorMessageOf`. `FileResponse.filename` đến từ server nên **làm sạch trước khi ghi ra đĩa**.

```kotlin
val file = AppKit.client.download("/api/activities/9/export/fit")        // FileResponse(status, bytes, contentType, contentDisposition)
if (file.status !in 200..299) error(AppKit.client.errorMessageOf(RawResponse(file.status, file.text(), file.requestId)))
val saveAs = file.filename                                                // "taper-2026-10-30-0700.fit" (chưa làm sạch)

val r = AppKit.client.upload("POST", "/api/me/avatar", listOf(FormPart("avatar", bytes, "a.png", "image/png")))
```

## Xử lý lỗi

Mọi thất bại là một `ApiError` có `kind`, `status` (0 = chưa có phản hồi), `requestId` (dán vào log máy chủ để tra) và `message` (lời của server nếu có,
không thì câu mặc định theo ngôn ngữ). Mỗi lỗi để lại **đúng một dòng log**. `isRetryable` cho biết có đáng thử lại không.

<!-- snippet: network-errors -->
```kotlin
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
```
<!-- /snippet -->

## Cache GET

Bật theo từng lời gọi (mặc định tắt). Chỉ cache GET thành công. Hết hạn mà mất mạng/máy chủ lỗi thì trả bản cũ (`staleIfError`).
Mỗi người dùng một ngăn riêng (`client.cache.scope = userId`); ghi thành công tự xoá cache của tài nguyên đó (`/api/items/9/archive` xoá `/api/items`);
đăng xuất hoặc hết phiên xoá sạch. Muốn còn sau khi mở lại app: `client.cache = ResponseCache(FileCacheStore(platformCacheDir("my-app")))`.

<!-- snippet: network-cache -->
```kotlin
val cached: User = AppKit.client.get("/api/v2/users/1", cache = CachePolicy(1.hours))   // 1 giờ không gọi lại; offline → bản cũ
val fresh: User = AppKit.client.get("/api/v2/users/1", cache = CachePolicy(1.hours, refresh = true)) // bỏ qua cache lần này
// ghi (POST/PUT/PATCH/DELETE) thành công tự xoá cache của tài nguyên đó; đăng xuất xoá hết
```
<!-- /snippet -->

## Phiên đăng nhập

<!-- snippet: network-session -->
```kotlin
val sessionJob = appScope.launch { AppKit.client.sessionExpired.collect { /* điều hướng về màn đăng nhập */ } }
AppKit.refreshSession()                      // làm mới phiên ngay, gọi từ đâu cũng được; nhiều nơi gọi cùng lúc chỉ đổi token một lần
AppKit.client.logout()                       // thu hồi trên server (nếu được), xoá token và cache cục bộ
```
<!-- /snippet -->

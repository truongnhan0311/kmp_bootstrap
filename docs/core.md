# appkit-core

Thứ **mọi app đều cần**, không dính mạng. Chỉ phụ thuộc coroutines, serialization, datetime, okio, cryptography.

```kotlin
// build.gradle.kts
commonMain.dependencies { implementation("kmp.bootstrap:appkit-core") }
```

Android: gọi `AppKitAndroid.init(context)` một lần ở `Application.onCreate` (cần cho lưu trữ, log, cache).

## Language — chữ hiện cho người dùng theo ngôn ngữ

Thứ tự chọn: app đặt (`Language.current`) → ngôn ngữ hệ thống → tiếng Anh. Có sẵn en, vi, zh, es cho các câu do kit sinh ra (lỗi mạng,
lỗi định dạng…). **Chỉ chữ hướng tới người dùng** đi qua `Language`; dòng log và thông báo cho lập trình viên luôn tiếng Anh.

<!-- snippet: core-language -->
```kotlin
Language.current = "vi"                              // app đặt; bỏ dòng này thì theo ngôn ngữ hệ thống, không có thì "en"
val vi = Language.text(Msg.NetworkUnreachable)       // "Không kết nối được máy chủ. …"
Language.register("fr", mapOf(Msg.Timeout to "Délai dépassé"))   // thêm ngôn ngữ / ghi đè một câu
Language.current = "fr"
val fr = Language.text(Msg.Timeout)                  // "Délai dépassé"
val fallback = Language.text(Msg.NotFound)           // fr chưa có câu này → tiếng Anh
```
<!-- /snippet -->

## AppStorage — lưu cục bộ

`prefs` cho lựa chọn thường (SharedPreferences / NSUserDefaults / file). `secrets` và `tokens` cho bí mật, nằm ở nơi mã hoá của máy
(Android Keystore, iOS Keychain; desktop chỉ là file 0600, **chưa mã hoá**). Mỗi `name` một ngăn riêng.

<!-- snippet: core-storage -->
```kotlin
val storage = AppStorage("my-app")                   // Android: gọi AppKitAndroid.init(context) một lần trước đó
storage.prefs.putBool("dark", true)                  // lựa chọn thường (giao diện, ngôn ngữ…)
storage.prefs.putInt("launches", 3)
storage.prefs.putJson("settings", AppSettings.serializer(), AppSettings("dark", 14))
val settings = storage.prefs.getJson("settings", AppSettings.serializer())   // null nếu chưa có hoặc dữ liệu cũ hỏng
storage.tokens.save(access = "jwt", refresh = "refresh")                  // token nằm ở nơi mã hoá của máy (Keystore/Keychain)
```
<!-- /snippet -->

## AppCrypto — mã hoá và giải mã

AES-256-GCM (mã hoá có xác thực: sửa một byte là giải mã thất bại, không ra rác). Khoá từ mật khẩu qua PBKDF2-SHA256 210.000 vòng, salt và IV
ngẫu nhiên mỗi lần. Dùng thư viện cryptography-kotlin, không tự cài thuật toán. Muốn cất bí mật **cục bộ** thì dùng `AppStorage.secrets`, không phải cái này.

<!-- snippet: core-crypto -->
```kotlin
val box = AppCrypto.encryptText("nội dung bí mật", password = "mật khẩu")    // chuỗi base64, lưu/gửi được
val text = AppCrypto.decryptText(box, password = "mật khẩu")                 // sai mật khẩu hoặc bị sửa → ném CryptoError
val key = AppCrypto.generateKey()                                            // hoặc dùng khoá 32 byte thô
val bytes = AppCrypto.decrypt(AppCrypto.encrypt("abc".encodeToByteArray(), key), key)
```
<!-- /snippet -->

## TtlCache — cache có hạn, dùng ở đâu cũng được

Không dính HTTP. `getOrLoad` an toàn khi nhiều nơi gọi cùng lúc: cùng một khoá chỉ chạy **một** lần tải. Lỗi khi tải không được lưu;
`staleIfError = true` thì trả bản cũ đã hết hạn thay vì ném lỗi. Đầy thì bỏ mục dùng lâu nhất. (Cache cho HTTP: xem [network.md](network.md#cache-get).)

<!-- snippet: core-ttlcache -->
```kotlin
val profiles = TtlCache<String, String>(ttl = 1.hours)
var loads = 0
val a = profiles.getOrLoad("user-1") { loads++; "Nhan" }     // chạy loader
val b = profiles.getOrLoad("user-1") { loads++; "không chạy" } // còn hạn → không gọi lại
profiles.remove("user-1")                                     // xoá tay; clear() xoá hết
```
<!-- /snippet -->

## AppLog — log xoay vòng, che bí mật

File tự xoay vòng theo dung lượng và xoá theo tuổi (mặc định 15 ngày). Mật khẩu che hẳn; token/khoá giữ 5 ký tự cuối để tra
(giá trị ngắn hơn 16 ký tự che hẳn). Ghi qua một hàng đợi nên không chặn luồng gọi. Dòng log luôn tiếng Anh.

<!-- snippet: core-log -->
```kotlin
val file = RotatingFileSink(platformLogDir("my-app"), maxBytes = 1_000_000, keepFiles = 5)   // xoay vòng, giữ 15 ngày
AppLog.install(ConsoleSink(), file)
AppLog.i("auth", "signed in")                                  // dòng log luôn tiếng Anh, dễ grep
AppLog.w("sync", "slow response")
val safe = Redactor.redact("password=hunter2 token=eyJhbGciOiJIUzI1NiJ9.payload.SIGNATURE12345")
// → password=[redacted] token=[redacted…12345]   (mật khẩu che hẳn, token giữ 5 ký tự cuối để tra)
```
<!-- /snippet -->

## AppTimers — hẹn giờ trong tiến trình

Chạy khi app **đang mở**. Cần chạy khi app đã tắt (nhắc giờ) thì dùng [appkit-reminder](reminder.md). `every` không bao giờ chồng lấn
(lần sau chỉ bắt đầu khi lần trước xong); lỗi trong khối chạy chỉ được ghi log, timer vẫn sống.

<!-- snippet: core-timers -->
```kotlin
val timers = AppTimers()
timers.after(50.milliseconds) { seen += "một lần" }                         // chạy một lần sau 50 ms
val poll = timers.every(40.milliseconds, immediately = true) { seen += "lặp" } // lặp, không bao giờ chồng lên nhau
val search = Debouncer(timers.scope, 60.milliseconds)
search.submit { seen += "a" }; search.submit { seen += "ab" }                // chỉ lần cuối chạy, sau khi im 60 ms
kotlinx.coroutines.delay(200)
poll.cancel(); timers.cancelAll()
```
<!-- /snippet -->

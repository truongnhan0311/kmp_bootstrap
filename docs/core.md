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
storage.prefs.putJson("settings", UiSettings.serializer(), UiSettings("dark", 14))
val settings = storage.prefs.getJson("settings", UiSettings.serializer())   // null nếu chưa có hoặc dữ liệu cũ hỏng
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

**Ký và kiểm bằng khoá chung (HMAC-SHA256)**, `Hex` (chữ thường, như server), so sánh không lộ thời gian, và chuỗi ngẫu nhiên lấy **đều** từ một bảng chữ (không lệch như `byte % n`):

<!-- snippet: core-hmac -->
```kotlin
val sig = Hex.encode(AppCrypto.hmacSha256("khoá chung".encodeToByteArray(), "GET\n/v1/me".encodeToByteArray()))   // hex chữ thường như server Go
val ok = AppCrypto.constantTimeEquals(Hex.decode(sig), AppCrypto.hmacSha256("khoá chung".encodeToByteArray(), "GET\n/v1/me".encodeToByteArray()))
val code = AppCrypto.randomString(8, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")     // mã dễ đọc, không có 0/O/1/I
```
<!-- /snippet -->

## QrCode — sinh mã QR

Thuần Kotlin, theo ISO/IEC 18004: chế độ byte (UTF-8), mức sửa lỗi `Low/Medium/Quartile/High`, phiên bản 1–40, tự chọn mặt nạ.
Mọi phiên bản × mức đều được kiểm bằng cách giải lại với ZXing; bit định dạng so với bảng của chuẩn.

<!-- snippet: core-qr -->
```kotlin
val qr = QrCode.encode("https://app.example.com/i/7FhQ2kLm")   // phiên bản nhỏ nhất vừa; còn chỗ thì nâng mức sửa lỗi
val dark = qr[0, 0]                                             // ma trận qr.size × qr.size, true = ô đen (chưa có viền)
val svg = qr.toSvg(border = 4)                                  // ghi ra file, gửi web… (trong Compose: QrImage của appkit-ui)
```
<!-- /snippet -->

`QrCode.encodeBytes(bytes, ecc, boostEcc, minVersion, mask)` cho dữ liệu nhị phân hay khi muốn cố định cỡ mã; quá 2953 byte (mức Low) thì ném `IllegalArgumentException`.

## LogExport và Gzip — gom log để gửi khi được yêu cầu

`LogExport.bundle` đọc nội dung file của `RotatingFileSink`, lọc theo mức và thời gian, sắp theo thời gian, che thêm email và IP
(IPv4 giữ /24, IPv6 giữ /48; mật khẩu/token đã được `Redactor` che khi ghi), ra NDJSON (`ts`, `lvl`, `comp`, `msg`), nén gzip và tính SHA-256.
Quá `maxBytes` thì bỏ dòng **cũ**, giữ dòng mới nhất. Tải lên bằng `LogUploader` của `appkit-network`.

`Gzip.compress` là gzip thuần Kotlin (DEFLATE mã Huffman cố định + LZ77), chạy giống nhau mọi nền tảng, nén log khoảng 4–10 lần;
được kiểm bằng `GZIPInputStream` của Java ở mọi độ dài khớp 3–258 và mọi nhóm khoảng cách.

<!-- snippet: core-log-export -->
```kotlin
// logText = RotatingFileSink.readAll(); since/maxBytes lấy từ yêu cầu của máy chủ
val bundle = LogExport.bundle(logText, LogExport.Options(minLevel = LogLevel.Info, sinceMillis = since, maxBytes = 5 * 1024 * 1024))
// bundle.gzip: NDJSON đã nén, đã che email / IP (giữ /24); bundle.sha256, bundle.lines, bundle.truncated (bỏ dòng cũ nếu quá lớn)
val small = Gzip.compress("bất kỳ dữ liệu nào".encodeToByteArray())       // gzip chuẩn, mở bằng gunzip ở máy chủ
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

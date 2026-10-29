# appkit-platform — dịch vụ của hệ điều hành

Việc **chỉ hệ điều hành làm được** qua MỘT interface cho mọi nền tảng, để màn hình không bao giờ biết đang chạy trên gì. Chỉ cần `appkit-core`.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-platform") }
```

| Hàm | Làm gì |
|---|---|
| `openUrl(url)` | mở trình duyệt / ứng dụng xử lý link |
| `copyToClipboard(text)` | chép vào clipboard |
| `shareText(text)` | hộp thoại chia sẻ của hệ điều hành; trả `false` nếu không mở được |
| `pickImage()` | bộ chọn ảnh → `PickedImage(bytes, filename, contentType)`; `null` nếu người dùng huỷ |
| `timezoneId()` | múi giờ IANA ("Asia/Ho_Chi_Minh") để chia ngày đúng theo người dùng |

Hai hàm tiện ích an toàn hơn gọi thẳng: **`openLink`** chỉ mở http/https/mailto/tel (chuỗi lạ như `javascript:`, `file:` bị từ chối), **`shareOrCopy`** rơi về copy khi hệ điều hành không có hộp thoại chia sẻ (desktop).

## Dùng trong màn hình

<!-- snippet: platform-screen -->
```kotlin
// Màn hình chỉ biết interface — chạy trên Android, iOS, desktop hay bản giả đều như nhau.
suspend fun shareAndAvatar(platform: PlatformServices, shareUrl: String): String? {
    platform.shareOrCopy("Xem buổi tập của mình: $shareUrl")     // mở hộp thoại chia sẻ; không có thì tự copy
    platform.openLink("https://example.com/help")               // chỉ mở http/https/mailto/tel — chuỗi lạ bị từ chối
    val image = platform.pickImage() ?: return null            // null nếu người dùng huỷ
    return "${image.filename} (${image.bytes.size} byte, ${image.contentType})"     // gửi image.bytes lên máy chủ bằng multipart
}
```
<!-- /snippet -->

## Bản thật và bản giả

<!-- snippet: platform-real -->
```kotlin
val platform = platformServices()          // Android: gọi AppKitAndroid.init(context) và AppKitPlatform.attach(this) ở MainActivity trước đó
val zone = platform.timezoneId()           // "Asia/Ho_Chi_Minh" — dùng để chia ngày đúng múi giờ người dùng
```
<!-- /snippet -->

`FakePlatformServices` ghi lại mọi lời gọi (`openedUrls`, `copied`, `shared`) và trả giá trị bạn đặt (`nextImage`, `timezone`, `shareWorks`) — dùng trong test và khi xem thử màn hình, không động tới hệ điều hành.

<!-- snippet: platform-fake -->
```kotlin
val fake = FakePlatformServices(timezone = "Asia/Ho_Chi_Minh", shareWorks = false)   // trong test: ghi lại lời gọi, không động tới hệ điều hành
fake.nextImage = kmp.bootstrap.appkit.platform.PickedImage(byteArrayOf(1, 2, 3), "avatar.png", "image/png")
val result = shareAndAvatar(fake, "https://app.example.com/s/abc")
```
<!-- /snippet -->

## Nối vào từng nền tảng

- **Android:** `MainActivity.onCreate` (trước `setContent`): `AppKitAndroid.init(applicationContext); AppKitPlatform.attach(this)` — Android bắt đăng ký bộ chọn ảnh trước khi Activity bắt đầu.
- **iOS:** không cần gì thêm; hộp thoại chia sẻ/chọn ảnh được trình bày từ view controller trên cùng.
- **Desktop:** `shareText` trả `false` (không có hộp thoại chia sẻ chung) nên `shareOrCopy` sẽ copy; `pickImage` mở hộp thoại tệp.

**Đã kiểm (JVM):** `openLink` chặn scheme lạ, `shareOrCopy`, bản giả, múi giờ thật của desktop. **Chưa kiểm** (cần thiết bị): Android (Intent, ClipboardManager, GetContent), iOS (UIActivityViewController, UIImagePickerController, UIPasteboard), hộp thoại tệp desktop.

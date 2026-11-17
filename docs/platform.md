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
| `shareFile(filename, bytes, mimeType)` | đưa một tệp ra khỏi app (Android: chia sẻ qua FileProvider; iOS: hộp thoại chia sẻ; desktop: thư mục Tải về). Trả `false` khi không làm được |
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

## Khoá app bằng khuôn mặt / vân tay / mật mã máy

`AppLock` (dùng `deviceAuth()`): người dùng bật một lần, sau đó mỗi lần mở app, hoặc quay lại sau 5 phút ở nền, phải mở khoá bằng
Face ID / Touch ID / vân tay / khuôn mặt, không có thì mật mã của máy. Mở khoá **trên máy**, không gọi máy chủ; phiên đã lưu giữ nguyên.

<!-- snippet: platform-applock -->
```kotlin
val lock = AppLock(auth, prefs)                              // trong app: AppLock(deviceAuth(), storage.prefs)
if (auth.available() != DeviceAuthKind.None) lock.enable()   // công tắc "Mở khoá bằng Face ID"; hỏi xác thực một lần
val screen = when (lock.state.value) {
    AppLock.State.Off, AppLock.State.Unlocked -> "app"
    AppLock.State.Locked -> "unlock"                         // nút gọi lock.unlock()
    AppLock.State.NeedsPassword -> "login"                   // đăng nhập xong thì lock.passwordLoginSucceeded()
}
lock.onBackground()                                          // vòng đời app (ON_STOP / sceneDidEnterBackground)
lock.onForeground()                                          // quá 5 phút thì tự về Locked
```
<!-- /snippet -->

| Trạng thái | Màn hình |
|---|---|
| `Off`, `Unlocked` | nội dung app |
| `Locked` | màn "Mở khoá" (nút gọi `unlock()`; huỷ thì vẫn khoá) |
| `NeedsPassword` | đăng nhập bằng mật khẩu: máy không còn khoá màn hình, hoặc sai quá nhiều lần (`LockedOut`); xong thì `passwordLoginSucceeded()` |

Đăng xuất gọi `signedOut()` (tắt khoá). Bật khoá bắt xác thực thành công một lần. `DeviceAuth.authenticate` dùng riêng được để xác nhận thao tác nhạy cảm.

- **Android:** `MainActivity` phải là `FragmentActivity` (vd `AppCompatActivity`), đã gọi `AppKitPlatform.attach(this)`. Dùng `BiometricPrompt` với `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`.
- **iOS:** thêm `NSFaceIDUsageDescription` vào `Info.plist` (vd "Mở khoá Recam bằng Face ID"), không có thì app bị dừng khi gọi Face ID. Dùng `LAPolicyDeviceOwnerAuthentication`.
- **Desktop:** luôn `NotAvailable` → `NeedsPassword`.
- **Giới hạn:** đây là cửa của giao diện; token vẫn nằm trong Keystore/Keychain như trước, chưa gắn với khoá cần sinh trắc học. **Phần Android/iOS chưa chạy trên máy thật.**

## Đăng nhập bằng khuôn mặt / vân tay / mật mã máy (có máy chủ)

Khác `AppLock` (chỉ mở khoá trên máy): `DeviceKeyLogin` **đăng nhập** với máy chủ, kiểu passkey. Máy giữ khoá riêng ECDSA P-256 trong
chip bảo mật (Android Keystore, iOS Secure Enclave), khoá chỉ ký sau lời nhắc của hệ điều hành; máy chủ giữ khoá công khai, gửi
challenge ngắn hạn dùng một lần, kiểm chữ ký rồi trả phiên mới. Không cất mật khẩu trên máy.

<!-- snippet: platform-devicekey -->
```kotlin
val login = DeviceKeyLogin(store, backend, prefs, deviceId = { "install-1" })   // trong app: deviceKeyStore(), storage.prefs, id lần cài
if (login.available()) login.enable(userId, "iPhone của Minh", "ios")            // công tắc ở màn Tài khoản (đang đăng nhập)
val who = login.lastUser                                                        // màn đăng nhập: có thì hiện "Đăng nhập bằng Face ID"
val screen = when (val r = login.login(who ?: userId, reason = "Đăng nhập Recam")) {
    is DeviceKeyLogin.Outcome.Success -> "home"                                 // lưu r.session như sau /auth/login
    DeviceKeyLogin.Outcome.Cancelled -> "login"                                 // ở lại, người dùng bấm lại được
    DeviceKeyLogin.Outcome.Disabled, DeviceKeyLogin.Outcome.LockedOut,
    DeviceKeyLogin.Outcome.Unavailable -> "password"                            // về mật khẩu; Disabled: báo "bật lại trong Tài khoản"
    is DeviceKeyLogin.Outcome.Error -> "retry"                                  // lỗi mạng
}
```
<!-- /snippet -->

Máy chủ cài `DeviceKeyBackend` bằng client HTTP của app. Ví dụ với API Recam (`PROTOCOL.md` 3.5):

<!-- snippet: platform-devicekey-backend -->
```kotlin
// Phía máy chủ Recam (PROTOCOL.md 3.5), viết bằng client HTTP của app. 401 device_key_not_registered → ném DeviceKeyNotRegistered.
class RecamDeviceKeys(private val http: Map<String, (Map<String, String>) -> Map<String, String>>) : DeviceKeyBackend<String> {
    override suspend fun register(deviceId: String, publicKey: String, name: String, platform: String) {
        http.getValue("POST /v1/auth/device-keys")(mapOf("device_id" to deviceId, "public_key" to publicKey, "name" to name, "platform" to platform))
    }
    override suspend fun challenge(userId: String, deviceId: String) =
        http.getValue("POST /v1/auth/device-challenge")(mapOf("user_id" to userId, "device_id" to deviceId)).getValue("challenge")
    override suspend fun login(challenge: String, signature: String) =
        http.getValue("POST /v1/auth/device-login")(mapOf("challenge" to challenge, "signature" to signature)).getValue("refresh_token")
    override suspend fun unregister(deviceId: String) { http.getValue("DELETE /v1/auth/device-keys/{id}")(mapOf("device_id" to deviceId)) }
}
```
<!-- /snippet -->

| Kết quả | Nghĩa | Màn hình |
|---|---|---|
| `Success(session)` | Máy chủ trả phiên | lưu như sau đăng nhập mật khẩu |
| `Cancelled` | Người dùng bấm huỷ | ở lại màn đăng nhập |
| `Disabled` | Máy chủ đã thu hồi, hoặc đổi khuôn mặt / vân tay làm khoá mất hiệu lực. Kit đã xoá khoá trên máy | mật khẩu, nhắc bật lại |
| `LockedOut` | Sai quá nhiều lần | mật khẩu |
| `Unavailable` | Máy không hỗ trợ, hoặc chưa bật | mật khẩu |
| `Error(cause)` | Lỗi mạng / máy chủ, tính năng vẫn bật | thử lại |

Định dạng (có vector khoá chung với server Recam, `DeviceKeyVectorTest`): khoá công khai base64 SPKI DER (Android) hoặc điểm 65 byte
(iOS); chữ ký base64 ECDSA SHA-256 DER trên chuỗi challenge UTF-8. Mỗi tài khoản một khoá (`alias(userId)`).
`allowPasscode = true` (mặc định) cho dùng mật mã máy; khoá khi đó không mất hiệu lực khi thêm khuôn mặt / vân tay trên iOS.

- **Android:** như `AppLock` (`FragmentActivity`, `AppKitPlatform.attach(this)`). Khoá `setUserAuthenticationRequired` + `setInvalidatedByBiometricEnrollment`,
  ký trong `BiometricPrompt` có `CryptoObject`. Mật mã máy chỉ từ Android 11 (API 30); bản cũ hơn chỉ sinh trắc học mạnh.
- **iOS:** thêm `NSFaceIDUsageDescription` vào `Info.plist`, chép lớp Swift dưới đây vào app và gán lúc khởi động
  `IosDeviceKeys.shared.store = SecureEnclaveDeviceKeyStore()` (Security/LocalAuthentication dùng từ Swift gọn và an toàn hơn cinterop).
  Simulator không có Secure Enclave: `createKey` trả `nil` → `enable` trả `false`.
- **Desktop:** `deviceKeyStore()` trả `null` → `Unavailable`.

```swift
import LocalAuthentication
import Security
import ComposeApp   // tên framework Kotlin của app

/// Khoá đăng nhập trong Secure Enclave (ECDSA P-256). allowPasscode: .userPresence (Face ID / Touch ID hoặc passcode);
/// không thì .biometryCurrentSet (đổi Face ID là khoá mất hiệu lực).
final class SecureEnclaveDeviceKeyStore: NSObject, DeviceKeyStore {
    private func tag(_ alias: String) -> Data { Data(alias.utf8) }

    func isAvailable(allowPasscode: Bool) -> Bool {
        LAContext().canEvaluatePolicy(allowPasscode ? .deviceOwnerAuthentication : .deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    func hasKey(alias: String) -> Bool {
        let q: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag(alias),
                                kSecUseAuthenticationUI as String: kSecUseAuthenticationUISkip]
        let s = SecItemCopyMatching(q as CFDictionary, nil)
        return s == errSecSuccess || s == errSecInteractionNotAllowed
    }

    func createKey(alias: String, allowPasscode: Bool) -> String? {
        deleteKey(alias: alias)
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(kCFAllocatorDefault, kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
                allowPasscode ? [.privateKeyUsage, .userPresence] : [.privateKeyUsage, .biometryCurrentSet], &error) else { return nil }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true,
                                            kSecAttrApplicationTag as String: tag(alias),
                                            kSecAttrAccessControl as String: access] as [String: Any],
        ]
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error), let pub = SecKeyCopyPublicKey(key),
              let raw = SecKeyCopyExternalRepresentation(pub, &error) as Data? else { return nil }
        return raw.base64EncodedString()   // điểm 65 byte; server nhận cả dạng này
    }

    func sign(alias: String, message: String, reason: String, allowPasscode: Bool, callback: @escaping (String?, String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {   // SecKeyCreateSignature chờ người dùng: không chạy trên main
            let context = LAContext()
            context.localizedReason = reason
            let q: [String: Any] = [kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: self.tag(alias),
                                    kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef as String: true,
                                    kSecUseAuthenticationContext as String: context]
            var item: CFTypeRef?
            let status = SecItemCopyMatching(q as CFDictionary, &item)
            guard status == errSecSuccess, let found = item else {
                DispatchQueue.main.async { callback(nil, status == errSecItemNotFound ? "invalidated" : "failed") }
                return
            }
            var error: Unmanaged<CFError>?
            let sig = SecKeyCreateSignature(found as! SecKey, .ecdsaSignatureMessageX962SHA256, Data(message.utf8) as CFData, &error) as Data?
            let code = error.map { ($0.takeRetainedValue() as Error as NSError).code } ?? 0
            DispatchQueue.main.async {
                if let sig = sig { callback(sig.base64EncodedString(), nil); return }
                switch code {
                case LAError.userCancel.rawValue, LAError.appCancel.rawValue, LAError.systemCancel.rawValue,
                     LAError.userFallback.rawValue, Int(errSecUserCanceled): callback(nil, "cancelled")
                case LAError.biometryLockout.rawValue: callback(nil, "locked_out")
                case LAError.biometryNotEnrolled.rawValue, Int(errSecAuthFailed): callback(nil, "invalidated")
                default: callback(nil, "failed")
                }
            }
        }
    }

    func deleteKey(alias: String) {
        SecItemDelete([kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: tag(alias)] as CFDictionary)
    }
}
```

**Đã kiểm (JVM):** luồng `DeviceKeyLogin` (bật, đăng nhập, huỷ, khoá mất hiệu lực, máy chủ thu hồi, lỗi mạng, nhiều tài khoản), vector khoá
với server Recam, định dạng chữ ký JCA. **Chưa kiểm:** biên dịch và chạy Android Keystore, Swift Secure Enclave trên máy thật (cần Android SDK, Mac).

## Nối vào từng nền tảng

- **Android:** `MainActivity.onCreate` (trước `setContent`): `AppKitAndroid.init(applicationContext); AppKitPlatform.attach(this)` — Android bắt đăng ký bộ chọn ảnh trước khi Activity bắt đầu.
- **iOS:** không cần gì thêm; hộp thoại chia sẻ/chọn ảnh được trình bày từ view controller trên cùng.
- **Android, `shareFile`:** khai FileProvider trong Manifest (authority mặc định `<applicationId>.files`, đổi bằng `AppKitPlatform.fileAuthority`):
  `<provider android:name="androidx.core.content.FileProvider" android:authorities="${applicationId}.files" android:exported="false" android:grantUriPermissions="true"><meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths"/></provider>`
  với `file_paths.xml` có `<cache-path name="exports" path="exports/"/>`.
- **Desktop:** `shareText` trả `false` (không có hộp thoại chia sẻ chung) nên `shareOrCopy` sẽ copy; `pickImage` mở hộp thoại tệp.

**Đã kiểm (JVM):** `openLink` chặn scheme lạ, `shareOrCopy`, bản giả, múi giờ thật của desktop. **Chưa kiểm** (cần thiết bị): Android (Intent, ClipboardManager, GetContent), iOS (UIActivityViewController, UIImagePickerController, UIPasteboard), hộp thoại tệp desktop.

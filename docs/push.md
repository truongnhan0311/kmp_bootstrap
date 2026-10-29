# Thông báo đẩy (FCM) — nối vào app

Phần dùng chung đã nằm trong `kit` (`kmp.bootstrap.appkit.push`). Mỗi app chỉ làm các bước nền tảng dưới đây.
Mẫu lấy từ qt-depot (`QtDepotMessagingService`, `AppDelegate.swift`) và Taper.

## Phía server (quan trọng nhất)
- Gửi payload **chỉ có `data`** (không có `notification` cấp cao nhất). Có `notification` thì Android ở nền tự hiện thông báo và **không gọi** `onMessageReceived` — app không chạy được code nào.
- `data` chuẩn: `type`, `id` (hoặc `uuid`), `title`, `body`, tuỳ chọn `silent=1` (chỉ cập nhật dữ liệu, không hiện/kêu).
- iOS: `content-available: 1` để hệ thống đánh thức app ~30 giây ở nền.
- Không đưa bí mật vào thông báo (màn hình khoá và máy chủ Google/Apple đều thấy).

## Dùng trong app (common)

<!-- snippet: push-register -->
```kotlin
val push = PushManager(client, prefs, DevicesPushContract(Endpoints.taper()), appVersion = "1.0",
    tokenSource = PushTokenSource { "fcm-token" })     // app thật: bỏ tham số này, kit tự lấy token FCM của nền tảng
val ok = push.registerIfNeeded(userKey = "user-1")      // sau đăng nhập và mỗi lần mở app; chỉ gọi server khi token/người dùng đổi
push.unregister()                                       // TRƯỚC khi đăng xuất (sau đó không còn quyền gọi)
```
<!-- /snippet -->

Điều hướng khi bấm thông báo:

<!-- snippet: push-navigation -->
```kotlin
// nền tảng gọi khi người dùng bấm thông báo (Android: AppKitPush.handleIntent, iOS: AppDelegate); app quan sát và điều hướng
PushNavigation.request(PushMessage.fromData(mapOf("type" to "activity", "uuid" to "abc")))
val target = PushNavigation.target.value        // trong Compose: val target by PushNavigation.target.collectAsState()
PushNavigation.consumed()                       // điều hướng xong thì xoá, để xoay màn hình không điều hướng lặp
```
<!-- /snippet -->

Ví dụ cũ hơn:
```kotlin
val push = PushManager(AppKit.client, AppKit.storage.prefs, DevicesPushContract(Endpoints.taper()), appVersion = "1.0")
push.registerIfNeeded(userKey = user.id)   // sau đăng nhập và mỗi lần mở app — chỉ gọi server khi token/người dùng đổi
push.unregister()                           // TRƯỚC khi đăng xuất
// điều hướng khi bấm thông báo:
val target by PushNavigation.target.collectAsState()
LaunchedEffect(target) { target?.let { navigate(it.type, it.id); PushNavigation.consumed() } }
```
Backend kiểu qt-depot: thay `DevicesPushContract(Endpoints.taper())` bằng `LegacyPushContract(Endpoints.qtDepot(), appKey = "qt-depot", appName = "QT Depot")`.
Backend khác: cài `PushContract` (2 hàm: đăng ký, gỡ).

## Android
1. `build.gradle`: plugin `com.google.gms.google-services`, đặt `google-services.json` của app (Firebase console → thêm app Android, đúng `applicationId`).
2. `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<service android:name="kmp.bootstrap.appkit.push.AppKitMessagingService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT"/></intent-filter>
</service>
<!-- Activity chính nên là launchMode="singleTask" để bấm thông báo gọi onNewIntent thay vì mở thêm bản thứ hai -->
```
3. `Application.onCreate`: `AppKitAndroid.init(this)` rồi
   `AppKitPush.configure(MainActivity::class.java, smallIcon = R.drawable.ic_stat, onMessage = { /* tải trước dữ liệu */ })`.
4. `MainActivity`: `AppKitPush.handleIntent(intent)` trong `onCreate` và `onNewIntent`; xin quyền bằng `AppKitPush.requestPermission(this)` lúc hợp lý (Android 13+).

## iOS
Firebase Messaging cho iOS chỉ có Swift nên Swift đẩy token sang Kotlin. Thêm vào `AppDelegate.swift` (cần `GoogleService-Info.plist` của app iOS, Capability *Push Notifications* + *Background Modes → Remote notifications*):
```swift
import FirebaseCore, FirebaseMessaging, UserNotifications
import Shared   // framework Kotlin

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    func application(_ app: UIApplication, didFinishLaunchingWithOptions o: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        FirebaseApp.configure()
        Messaging.messaging().delegate = self
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
            if granted { DispatchQueue.main.async { app.registerForRemoteNotifications() } }
        }
        Messaging.messaging().token { token, _ in if let token { PushTokens.shared.set(token: token) } } // lấy ngay lúc khởi động
        return true
    }
    func application(_ app: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken d: Data) { Messaging.messaging().apnsToken = d }
    func messaging(_ m: Messaging, didReceiveRegistrationToken t: String?) { if let t { PushTokens.shared.set(token: t) } }

    // app đang mở: vẫn hiện thông báo
    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification, withCompletionHandler h: @escaping (UNNotificationPresentationOptions) -> Void) { h([.banner, .sound]) }

    // người dùng bấm thông báo (kể cả mở app từ trạng thái đã tắt)
    func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse, withCompletionHandler h: @escaping () -> Void) {
        let u = r.notification.request.content.userInfo
        PushNavigation.shared.request(message: PushMessage.Companion.shared.fromData(data: [
            "type": u["type"] as? String, "id": u["id"] as? String, "uuid": u["uuid"] as? String]))
        h()
    }
}
```
Không đặt số badge trong `willPresent` (mỗi thông báo cũ vừa xử lý sẽ ghi đè lại badge); xoá badge ở `applicationDidBecomeActive`.

## Đã kiểm / chưa kiểm
- **Đã kiểm (test JVM, không cần thiết bị):** đăng ký một lần rồi thôi, đăng ký lại khi đổi token/người dùng, server từ chối thì lần sau thử lại, `deviceId` bền và dùng khi gỡ, hai hợp đồng Taper/qt-depot, đọc payload.
- **Chưa kiểm:** toàn bộ phần Android/iOS ở trên (`Push.android.kt`, `Push.ios.kt`, Swift) — chưa biên dịch và chưa thử với Firebase thật. Chỉ chủ dự án mới thử được trên máy thật.

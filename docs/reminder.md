# appkit-reminder

Nhắc giờ bằng **thông báo cục bộ**, chạy cả khi app đã tắt (Android `AlarmManager`, iOS `UNUserNotificationCenter`). Chỉ cần `appkit-core`.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-reminder") }
```

Mỗi nhắc có `id` do app đặt: đặt lại cùng `id` là **thay**. Nhắc lặp (`Daily`, `Weekly`) giữ **đúng giờ trên đồng hồ** qua giờ mùa hè
(8:00 vẫn là 8:00, có test), không cộng cứng 24 giờ. Nhắc một lần đã qua giờ bị từ chối (`IllegalArgumentException`).

<!-- snippet: reminder-schedule -->
```kotlin
val reminders = Reminders(platformReminderBackend("my-app"), MemoryKeyValueStore())   // app thật: AppKit.storage.prefs
reminders.schedule(Reminder(id = "ride", title = "Đi đạp", body = "Buổi nền tảng 90 phút", atEpochMs = tomorrow7am))
reminders.schedule(Reminder("weigh-in", "Cân nặng", atEpochMs = tomorrow7am, repeat = Repeat.Daily))   // lặp hằng ngày, đúng giờ qua giờ mùa hè
reminders.schedule(Reminder("ride", "Đi đạp (đổi giờ)", atEpochMs = tomorrow7am + 3_600_000))        // cùng id = THAY, không tạo thêm
val pending = reminders.list()                       // 2 nhắc: ride, weigh-in
reminders.cancel("weigh-in")                         // huỷ một nhắc; cancelAll() huỷ hết
reminders.restoreAll()                               // gọi lúc mở app: nạp lại các nhắc còn hiệu lực (Android còn tự gọi sau khi máy khởi động lại)
```
<!-- /snippet -->

## Nối vào Android

1. `Application.onCreate`:
   ```kotlin
   AppKitAndroid.init(this)
   AppKitReminders.install(Reminders(platformReminderBackend("my-app"), AppKit.storage.prefs), smallIcon = R.drawable.ic_stat)
   ```
   (receiver chạy khi app chưa dựng UI nên cần chỗ lấy `Reminders` từ đây).
2. Manifest của module đã khai báo receiver + quyền `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` (tự gộp vào app). Android 13+: xin quyền hiện thông báo bằng `AppKitPush.requestPermission(activity)`.
3. Bấm thông báo mở app kèm `appkit_push_type=reminder`, `appkit_push_id=<id>` — cùng khoá với `AppKitPush.handleIntent` nên điều hướng dùng chung `PushNavigation`.
4. Báo thức dùng `setAndAllowWhileIdle` (không cần quyền báo thức chính xác): có thể trễ vài phút khi máy tiết kiệm pin. Máy khởi động lại → `BootReceiver` tự nạp lại.

## Nối vào iOS

Xin quyền như push (`UNUserNotificationCenter.requestAuthorization`). Nhắc lặp dùng lịch lặp của hệ điều hành nên tự nổ kể cả app tắt. iOS giới hạn **64 thông báo đang chờ** cho mỗi app.

## Đã kiểm / chưa kiểm

Đã kiểm (JVM): đặt/thay/huỷ, nạp lại sau khởi động lại, nhắc lặp qua DST, tuần lặp từ ngày rất xa, nổ thật trên desktop. **Chưa kiểm:** Android và iOS (cần thiết bị).

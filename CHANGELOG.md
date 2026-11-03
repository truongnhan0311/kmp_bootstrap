# Changelog

Phiên bản theo `0.x`: API còn có thể đổi, mỗi thay đổi phá API được ghi ở đây.

## 0.1.0
- **Sửa lỗi build trên Mac/CI** (phát hiện khi dựng Taper trên Codemagic, 02–03/11/2026; không lộ ra ở `-PdesktopOnly=true`): (1) mọi `*/android.gradle.kts` gọi thẳng `com.android.build.gradle.LibraryExtension` → "Unresolved reference: android"; nay cấu hình bằng `withGroovyBuilder` (không phụ thuộc lớp AGP). (2) `appkit-network` dùng `val iosMain by getting` → "KotlinSourceSet with name 'iosMain' not found"; nay lấy source set theo từng target (`iosArm64Main`…). Hai lỗi này sẽ làm hỏng mọi app dùng kit khi dựng bản Android/iOS đầy đủ.
- `appkit-core`: `Navigator` (ngăn xếp điều hướng), `DeepLinks` (link → route), `AppSettings` (giao diện + ngôn ngữ được nhớ, quy tắc lựa chọn gần nhất thắng), `ThemeMode` (chuyển từ `appkit-ui`).
- `appkit-network`: `Session<U>` (Loading/SignedOut/SignedIn, khôi phục khi mất mạng, tự về đăng nhập khi hết phiên), `AuthedClient.getBytes`, `logoutLocal`.
- `appkit-platform` (mới): `PlatformServices` (openUrl, copyToClipboard, shareText, pickImage, timezoneId), `FakePlatformServices`, `openLink`, `shareOrCopy`.
- `appkit-ui`: `NavHost` + `BackHandler`, `AsyncAvatar` + `decodeImage` + `ImageCache`, `AppTheme(…, settings)`.
- `tools/new-app.mjs`: sinh dự án app mới (Android + iOS + desktop) nối sẵn kit.
- `appkit-core`: Language (en/vi/zh/es), AppLog (xoay vòng + che bí mật), AppStorage (prefs + két an toàn), AppCrypto (AES-256-GCM), TtlCache, AppTimers/Debouncer, Jwt.
- `appkit-network`: HttpTransport (failover, thử lại GET, phân loại lỗi), AuthedClient (JWT, làm mới phiên single-flight), get/post/put/patch/delete + body @Serializable, ApiError có kind, cache GET, Endpoints, AppKit.start().
- `appkit-push`: PushManager (Taper/qt-depot), PushNavigation, FCM Android + iOS (chưa kiểm trên thiết bị).
- `appkit-reminder`: nhắc giờ cục bộ, nhắc lặp đúng giờ qua DST (Android/iOS chưa kiểm trên thiết bị).
- `appkit-ui`: component Material 3 kiểu shadcn, bộ sinh theme từ CSS, layout kiểu Tailwind (`Stack/Inline/Grid/Wrap`, `Modifier.p/px/rounded/cardStyle`), khung màn (`AppScaffold/AppHeader/Page/TabBar`), block kiểu Bootstrap (`Hero/StatGrid/FeatureGrid/List/Cta`), `StarterApp` mẫu 5 tab (Trang chủ, Hoạt động, Tìm kiếm, Hồ sơ, Cài đặt), dữ liệu mẫu tập trung ở `StarterData.Dummy` (truyền `StarterApp(data = …)` để thay bằng dữ liệu thật), các trang mẫu Đăng nhập/Đăng ký/Chi tiết/Lịch/Xu hướng (`StarterPages.kt`) với điều hướng `StarterRoute`, `BarChart`/`LineChart`, icon SVG (`AppIcons` sinh từ `appkit-ui/icons/*.svg` bằng `tools/svg-to-kotlin.mjs`; `SvgParser` ở `appkit-core`). `AppButton` đổi `small: Boolean` thành `size: ButtonSize` và thêm `variant` Secondary/Link, `fullWidth`, `leading`.

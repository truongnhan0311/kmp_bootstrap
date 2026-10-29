# Changelog

Phiên bản theo `0.x`: API còn có thể đổi, mỗi thay đổi phá API được ghi ở đây.

## 0.1.0
- `appkit-core`: Language (en/vi/zh/es), AppLog (xoay vòng + che bí mật), AppStorage (prefs + két an toàn), AppCrypto (AES-256-GCM), TtlCache, AppTimers/Debouncer, Jwt.
- `appkit-network`: HttpTransport (failover, thử lại GET, phân loại lỗi), AuthedClient (JWT, làm mới phiên single-flight), get/post/put/patch/delete + body @Serializable, ApiError có kind, cache GET, Endpoints, AppKit.start().
- `appkit-push`: PushManager (Taper/qt-depot), PushNavigation, FCM Android + iOS (chưa kiểm trên thiết bị).
- `appkit-reminder`: nhắc giờ cục bộ, nhắc lặp đúng giờ qua DST (Android/iOS chưa kiểm trên thiết bị).
- `appkit-ui`: component Material 3 kiểu shadcn, bộ sinh theme từ CSS, layout kiểu Tailwind (`Stack/Inline/Grid/Wrap`, `Modifier.p/px/rounded/cardStyle`), khung màn (`AppScaffold/AppHeader/Page/TabBar`), block kiểu Bootstrap (`Hero/StatGrid/FeatureGrid/List/Cta`), `StarterHome` mẫu. `AppButton` đổi `small: Boolean` thành `size: ButtonSize` và thêm `variant` Secondary/Link, `fullWidth`, `leading`.

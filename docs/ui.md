# appkit-ui

Component Material 3 đặt tên và API theo shadcn (Card, Button, Input, Badge, Alert…) cùng theme sinh từ **CSS của web**, để web và app dùng chung một bảng màu.
Không phụ thuộc module nào khác. Thêm component: viết một `@Composable` chỉ đọc `A.colors` / `A.type`, không giữ màu cứng.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-ui") }
```

## Theme từ CSS

`tools/shadcn-theme.mjs` đọc biến `--background`, `--primary`, `--muted-foreground`… (hex, rgb, hsl, **oklch** của shadcn mới, `var()` lồng nhau)
và sinh `AppColors` sáng/tối. Token thiếu rơi về token liên quan; thiếu hẳn thì báo lỗi rõ. Token riêng của app (vùng công suất, màu biểu đồ…)
vào `colors.extra("tên")`. Chạy test của bộ sinh: `node --test tools/`.

<!-- snippet: ui-theme -->
```kotlin
// 1) sinh màu từ CSS của web (một lần, hoặc mỗi khi đổi màu):
//    node tools/shadcn-theme.mjs ../web/src/index.css --package com.acme --name Acme --out src/commonMain/kotlin/com/acme/AcmeColors.kt
//    (shadcn mặc định: :root sáng, .dark tối;  nếu CSS khác thì thêm --light "<selector>" --dark "<selector>")
// 2) bọc app:
@Composable
fun AcmeApp(dark: AppColors, light: AppColors) {
    AppTheme(light = light, dark = dark, mode = ThemeMode.System) {      // System / Light / Dark
        LoginScreen()
    }
}
```
<!-- /snippet -->

## Component

Quy ước chữ (đổi được qua `AppTypography`): `sans` cho giao diện, `mono` cho **mọi con số**, `serif` cho câu do người/AI viết.

<!-- snippet: ui-components -->
```kotlin
@Composable
fun LoginScreen() {
    ScreenHeader("Đăng nhập", subtitle = "Dùng tài khoản của bạn")       // tiêu đề + câu phụ (chữ serif)
    AppCard {                                                            // thẻ shadcn: nền card, viền border
        AppField(label = "Email", value = "", onChange = {})             // numeric = true cho ô số (chữ mono)
        Gap(8)
        AppButton("Đăng nhập", onClick = {})                             // variant: Default / Outline / Destructive / Ghost
        AppAlert("Sai mật khẩu", destructive = true)                     // thông báo lỗi
    }
    Badge("beta"); Stat("FTP", "250 W")                                  // nhãn nhỏ, ô chỉ số
}
```
<!-- /snippet -->

**Chưa kiểm:** module biên dịch được nhưng chưa chạy lúc runtime / chưa chụp màn hình ở môi trường dựng (không tải được `androidx.collection`).

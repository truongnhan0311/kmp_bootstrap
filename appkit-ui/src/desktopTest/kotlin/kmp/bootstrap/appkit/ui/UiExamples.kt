package kmp.bootstrap.appkit.ui

import androidx.compose.runtime.Composable

/** VÍ DỤ của appkit-ui — chỉ BIÊN DỊCH (không chạy được ở môi trường dựng); xem ghi chú ở CoreExamples. */
// region: ui-theme
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
// endregion

// region: ui-components
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
// endregion

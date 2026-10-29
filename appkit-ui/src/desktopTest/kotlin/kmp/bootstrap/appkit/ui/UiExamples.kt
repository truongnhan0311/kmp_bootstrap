package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

// region: ui-layout
@Composable
fun LayoutDemo() {
    Stack(gap = 4, modifier = Modifier.p(5)) {                       // flex-col gap-4 p-5
        Inline(gap = 2) { Badge("mới"); Txt("Tiêu đề") }              // flex items-center gap-2
        Grid(columns = 2, gap = 3) {                                  // grid grid-cols-2 gap-3
            cell { AppCard { Stat("Thể lực", "43") } }
            cell { AppCard { Stat("Mệt", "61") } }
        }
        Box(Modifier.p(3).cardStyle()) { Txt("Khối tự vẽ trông như thẻ") }   // p-3 rounded-lg border bg-card
        AppButton("Lưu", onClick = {}, variant = ButtonVariant.Outline, size = ButtonSize.Sm, fullWidth = true)
    }
}
// endregion

// region: ui-blocks
@Composable
fun HomeWithBlocks() {
    Page {
        HeroBlock("Chào buổi sáng", "Hôm nay bạn muốn làm gì?", action = { AppButton("Bắt đầu", onClick = {}) })
        StatGridBlock(title = "Tuần này", items = listOf(StatItem("Buổi tập", "4"), StatItem("Thể lực", "43")))
        ListBlock("Gần đây", items = listOf(ListRow("Đạp buổi sáng", "1h13 · 32 km", trailing = "81 TSS")))
        CtaBlock("Nối nguồn dữ liệu", "Buổi tập sẽ tự về.", actionLabel = "Nối ngay", onAction = {})
    }
}
// endregion

// region: ui-scaffold
@Composable
fun AppFrame(dark: AppColors, light: AppColors) {
    StarterHome(dark, light)        // trang chủ mẫu đầy đủ: header + nội dung (các block) + tab dưới — chép StarterHome.kt rồi thay dữ liệu
}
// endregion

// region: ui-icons
@Composable
fun IconsDemo() {
    Inline(gap = 3) {
        AppIcon(AppIcons.Home)                                                  // 20dp, màu chữ (icon là file .svg → AppIcons)
        AppIcon(AppIcons.Search, size = 24.dp, color = A.colors.primary)        // đổi cỡ và màu
        AppButton("Thêm buổi", onClick = {}, icon = AppIcons.Plus)              // nút có icon (icon cùng màu chữ nút)
    }
    TabBar(listOf(TabItem("home", "Trang chủ", AppIcons.Home), TabItem("me", "Hồ sơ", AppIcons.User)), selected = "home") { }   // tab có icon
    AppIcon("<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\"><path d=\"M5 12h14\"/></svg>")           // hoặc SVG bất kỳ dạng chuỗi
}
// endregion

package kmp.bootstrap.appkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * TRANG CHỦ MẪU (dữ liệu giả) — chép file này vào app rồi thay dữ liệu thật. Ghép từ khung màn (AppScaffold/AppHeader/Page/TabBar)
 * và các block (Hero, StatGrid, FeatureGrid, List, Cta). Không có logic, không gọi mạng: chỉ để thấy bố cục và dùng làm điểm xuất phát.
 */
@Composable
fun StarterHome(dark: AppColors, light: AppColors, mode: ThemeMode = ThemeMode.System) {
    AppTheme(light = light, dark = dark, mode = mode) {
        var tab by remember { mutableStateOf("home") }
        val tabs = listOf(TabItem("home", "Trang chủ"), TabItem("list", "Danh sách"), TabItem("settings", "Cài đặt"))
        AppScaffold(
            topBar = { AppHeader("My App", actions = { AppButton("Đăng xuất", onClick = {}, variant = ButtonVariant.Outline, size = ButtonSize.Sm) }) },
            bottomBar = { TabBar(tabs, tab) { tab = it } },
        ) {
            Page {
                HeroBlock("Chào buổi sáng", "Hôm nay bạn muốn làm gì?", action = { AppButton("Bắt đầu", onClick = {}) })
                StatGridBlock(title = "Tuần này", items = listOf(
                    StatItem("Buổi tập", "4"), StatItem("Tổng thời gian", "5h20"),
                    StatItem("Thể lực", "43", A.colors.primary), StatItem("Mệt", "61", A.colors.destructive),
                ))
                FeatureGridBlock(title = "Lối tắt", items = listOf(
                    Feature("Lịch", "Xem các buổi theo ngày"), Feature("Xu hướng", "Biểu đồ 6 tuần"),
                    Feature("Kế hoạch", "Buổi tập tuần tới"), Feature("Nguồn dữ liệu", "Nối thiết bị"),
                ))
                ListBlock("Gần đây", hint = "mới nhất trước", items = listOf(
                    ListRow("Đạp buổi sáng", "29/10 · 1h13 · 32 km", trailing = "81 TSS"),
                    ListRow("Đạp nhóm Thủ Đức", "27/10 · 2h05 · 61 km", trailing = "142 TSS"),
                    ListRow("Hồi phục", "26/10 · 45 phút · 15 km", trailing = "28 TSS"),
                ))
                CtaBlock("Nối nguồn dữ liệu", "Buổi tập sẽ tự về, không cần nhập tay.", actionLabel = "Nối ngay", onAction = {})
            }
        }
    }
}

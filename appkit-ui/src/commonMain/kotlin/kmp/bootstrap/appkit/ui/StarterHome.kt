package kmp.bootstrap.appkit.ui

import androidx.compose.runtime.Composable
import kmp.bootstrap.appkit.SvgIcon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * APP MẪU 5 TAB (dữ liệu giả) — chép file này vào app rồi thay dữ liệu thật. Ghép từ khung màn (AppScaffold/AppHeader/Page/TabBar),
 * icon (AppIcon) và các block (Hero, StatGrid, FeatureGrid, List, Cta). Không có logic, không gọi mạng: chỉ để thấy bố cục và làm điểm xuất phát.
 *
 * Thêm một tab = thêm một giá trị vào [StarterTab] và một nhánh `when` ở [StarterApp]; thêm trang con = thêm một [StarterRoute].
 * Các trang còn lại (Đăng nhập, Đăng ký, Chi tiết, Lịch, Xu hướng) ở StarterPages.kt.
 */
enum class StarterTab(val label: String, val icon: SvgIcon) {
    Home("Trang chủ", AppIcons.Home), Activities("Hoạt động", AppIcons.List), Search("Tìm kiếm", AppIcons.Search),
    Profile("Hồ sơ", AppIcons.User), Settings("Cài đặt", AppIcons.Settings);

    companion object { val items = entries.map { TabItem(it, it.label, it.icon) } }
}

/** Các "màn" của app mẫu. Auth (Login/Register) toàn màn hình; Main là 5 tab; còn lại là trang con có nút quay lại. */
sealed interface StarterRoute {
    data object Login : StarterRoute
    data object Register : StarterRoute
    data class Main(val tab: StarterTab = StarterTab.Home) : StarterRoute
    data class Detail(val title: String) : StarterRoute
    data object Calendar : StarterRoute
    data object Trends : StarterRoute
}

@Composable
fun StarterHome(dark: AppColors, light: AppColors, mode: ThemeMode = ThemeMode.System, initial: StarterTab = StarterTab.Home) =
    StarterApp(dark, light, mode, StarterRoute.Main(initial))

@Composable
fun StarterApp(dark: AppColors, light: AppColors, mode: ThemeMode = ThemeMode.System, initial: StarterRoute = StarterRoute.Login) {
    AppTheme(light = light, dark = dark, mode = mode) {
        var route by remember { mutableStateOf(initial) }
        var back by remember { mutableStateOf<StarterRoute>(StarterRoute.Main()) }   // trang con quay lại đâu
        fun open(r: StarterRoute) { if (route is StarterRoute.Main) back = route; route = r }
        when (val r = route) {
            StarterRoute.Login -> AppScaffold { StarterLoginPage(onLogin = { route = StarterRoute.Main() }, onRegister = { route = StarterRoute.Register }) }
            StarterRoute.Register -> AppScaffold { StarterRegisterPage(onDone = { route = StarterRoute.Main() }, onBack = { route = StarterRoute.Login }) }
            else -> {
                val tab = (r as? StarterRoute.Main)?.tab ?: (back as? StarterRoute.Main)?.tab ?: StarterTab.Home
                val title = when (r) {
                    is StarterRoute.Main -> if (r.tab == StarterTab.Home) "My App" else r.tab.label
                    is StarterRoute.Detail -> "Chi tiết buổi tập"; StarterRoute.Calendar -> "Lịch"; StarterRoute.Trends -> "Xu hướng"; else -> ""
                }
                AppScaffold(
                    topBar = {
                        AppHeader(title, onBack = if (r is StarterRoute.Main) null else ({ route = back }), actions = {
                            AppIcon(AppIcons.Bell, size = androidx.compose.ui.unit.Dp(22f), color = A.colors.mutedForeground)
                            if (r is StarterRoute.Main) AppButton("Đăng xuất", onClick = { route = StarterRoute.Login }, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
                        })
                    },
                    bottomBar = { TabBar(StarterTab.items, tab) { route = StarterRoute.Main(it) } },
                ) {
                    when (r) {
                        is StarterRoute.Main -> when (r.tab) {
                            StarterTab.Home -> StarterHomeScreen(::open) { route = StarterRoute.Main(it) }
                            StarterTab.Activities -> StarterActivitiesScreen { open(StarterRoute.Detail(it)) }
                            StarterTab.Search -> StarterSearchScreen { open(StarterRoute.Detail(it)) }
                            StarterTab.Profile -> StarterProfileScreen()
                            StarterTab.Settings -> StarterSettingsScreen()
                        }
                        is StarterRoute.Detail -> StarterDetailPage(r.title)
                        StarterRoute.Calendar -> StarterCalendarPage { open(StarterRoute.Detail(it)) }
                        StarterRoute.Trends -> StarterTrendsPage()
                        else -> Unit
                    }
                }
            }
        }
    }
}

private val recent = listOf(
    ListRow("Đạp buổi sáng", "29/10 · 1h13 · 32 km", trailing = "81 TSS", icon = AppIcons.Calendar),
    ListRow("Đạp nhóm Thủ Đức", "27/10 · 2h05 · 61 km", trailing = "142 TSS", icon = AppIcons.Calendar),
    ListRow("Hồi phục", "26/10 · 45 phút · 15 km", trailing = "28 TSS", icon = AppIcons.Calendar),
)

/** Gắn hành động mở chi tiết vào các hàng. */
private fun List<ListRow>.opening(onOpen: (String) -> Unit) = map { r -> r.copy(onClick = { onOpen(r.title) }, chevron = true) }

@Composable
fun StarterHomeScreen(open: (StarterRoute) -> Unit = {}, goTab: (StarterTab) -> Unit = {}) = Page {
    HeroBlock("Chào buổi sáng", "Hôm nay bạn muốn làm gì?", action = { AppButton("Bắt đầu", onClick = {}, icon = AppIcons.Plus) })
    StatGridBlock(title = "Tuần này", items = listOf(
        StatItem("Buổi tập", "4"), StatItem("Tổng thời gian", "5h20"),
        StatItem("Thể lực", "43", A.colors.primary), StatItem("Mệt", "61", A.colors.destructive),
    ))
    FeatureGridBlock(title = "Lối tắt", items = listOf(
        Feature("Lịch", "Xem các buổi theo ngày", icon = AppIcons.Calendar, onClick = { open(StarterRoute.Calendar) }),
        Feature("Xu hướng", "Biểu đồ 6 tuần", icon = AppIcons.Chart, onClick = { open(StarterRoute.Trends) }),
        Feature("Tìm kiếm", "Theo tên hoặc thẻ", icon = AppIcons.Search, onClick = { goTab(StarterTab.Search) }),
        Feature("Nhắc giờ", "Đặt lịch tập", icon = AppIcons.Bell),
    ))
    ListBlock("Gần đây", hint = "mới nhất trước", items = recent.opening { open(StarterRoute.Detail(it)) })
    CtaBlock("Nối nguồn dữ liệu", "Buổi tập sẽ tự về, không cần nhập tay.", actionLabel = "Nối ngay", onAction = {})
}

@Composable
fun StarterActivitiesScreen(onOpen: (String) -> Unit = {}) = Page {
    ScreenHeader("Hoạt động của tôi", "Các buổi đã đạp, mới nhất trước.")
    ListBlock("Tháng 10", hint = "12 buổi", items = (recent + recent.map { it.copy(title = it.title + " (2)") }).opening(onOpen))
    ListBlock("Tháng 9", items = emptyList(), empty = "Chưa có buổi nào")
}

@Composable
fun StarterSearchScreen(onOpen: (String) -> Unit = {}) = Page {
    var query by remember { mutableStateOf("") }
    ScreenHeader("Tìm kiếm", "Theo tên hoặc thẻ của buổi tập.")
    AppField(label = "Từ khoá", value = query, onChange = { query = it }, placeholder = "ví dụ: đạp nhóm")
    if (query.isBlank()) {
        Section("Gần đây") { BadgesBlock(listOf("đạp nhóm", "hồi phục", "Thủ Đức", "interval")) }
    } else {
        val hits = recent.filter { it.title.contains(query, ignoreCase = true) }
        if (hits.isEmpty()) EmptyState("Không thấy “$query”", message = "Thử từ khoá khác hoặc bớt chữ.") else ListBlock("Kết quả", hint = "${hits.size} buổi", items = hits.opening(onOpen))
    }
}

@Composable
fun StarterProfileScreen() = Page {
    Inline(gap = 4) {
        Avatar("Nhan Nguyen", size = 56)
        Stack(gap = 0) { Txt("Nhan Nguyen", A.type.section.copy(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp))); Muted("nhan@example.com") }
    }
    StatGridBlock(title = "Chỉ số của bạn", items = listOf(StatItem("FTP", "250 W"), StatItem("Cân nặng", "71,5 kg"), StatItem("W/kg", "3,5", A.colors.primary), StatItem("Nhịp tim ngưỡng", "168")))
    ListBlock("Tài khoản", items = listOf(
        ListRow("Thông tin cá nhân", icon = AppIcons.User, chevron = true),
        ListRow("Nguồn dữ liệu", icon = AppIcons.Chart, chevron = true),
        ListRow("Thông báo", icon = AppIcons.Bell, chevron = true),
    ))
}


@Composable
fun StarterSettingsScreen() = Page {
    var mode by remember { mutableStateOf(ThemeMode.System) }
    var lang by remember { mutableStateOf("vi") }
    ScreenHeader("Cài đặt")
    Section("Giao diện") {
        Segmented(listOf(ThemeMode.System to "Theo máy", ThemeMode.Light to "Sáng", ThemeMode.Dark to "Tối"), mode, { mode = it })
    }
    Section("Ngôn ngữ") {
        Segmented(listOf("vi" to "Tiếng Việt", "en" to "English", "zh" to "中文", "es" to "Español"), lang, { lang = it })
    }
    ListBlock("Khác", items = listOf(ListRow("Về ứng dụng", "Phiên bản 1.0", icon = AppIcons.Check), ListRow("Điều khoản", chevron = true)))
    AppButton("Đăng xuất", onClick = {}, variant = ButtonVariant.Destructive, fullWidth = true)
}

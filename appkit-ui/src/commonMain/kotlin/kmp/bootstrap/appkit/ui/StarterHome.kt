package kmp.bootstrap.appkit.ui

import kmp.bootstrap.appkit.ThemeMode
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
fun StarterHome(dark: AppColors, light: AppColors, mode: ThemeMode = ThemeMode.System, initial: StarterTab = StarterTab.Home, data: StarterData = StarterData.Dummy) =
    StarterApp(dark, light, mode, StarterRoute.Main(initial), data)

@Composable
fun StarterApp(dark: AppColors, light: AppColors, mode: ThemeMode = ThemeMode.System, initial: StarterRoute = StarterRoute.Login, data: StarterData = StarterData.Dummy) {
    AppTheme(light = light, dark = dark, mode = mode) {
        var route by remember { mutableStateOf(initial) }
        var back by remember { mutableStateOf<StarterRoute>(StarterRoute.Main()) }   // trang con quay lại đâu
        fun open(r: StarterRoute) { if (route is StarterRoute.Main) back = route; route = r }
        when (val r = route) {
            StarterRoute.Login -> AppScaffold { StarterLoginPage(data, onLogin = { route = StarterRoute.Main() }, onRegister = { route = StarterRoute.Register }) }
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
                            StarterTab.Home -> StarterHomeScreen(data, ::open) { route = StarterRoute.Main(it) }
                            StarterTab.Activities -> StarterActivitiesScreen(data) { open(StarterRoute.Detail(it)) }
                            StarterTab.Search -> StarterSearchScreen(data) { open(StarterRoute.Detail(it)) }
                            StarterTab.Profile -> StarterProfileScreen(data)
                            StarterTab.Settings -> StarterSettingsScreen()
                        }
                        is StarterRoute.Detail -> StarterDetailPage(data, r.title)
                        StarterRoute.Calendar -> StarterCalendarPage(data) { open(StarterRoute.Detail(it)) }
                        StarterRoute.Trends -> StarterTrendsPage(data)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Gắn hành động mở chi tiết vào các hàng. */
private fun List<ListRow>.opening(onOpen: (String) -> Unit) = map { r -> r.copy(onClick = { onOpen(r.title) }, chevron = true) }

@Composable
fun StarterHomeScreen(data: StarterData = StarterData.Dummy, open: (StarterRoute) -> Unit = {}, goTab: (StarterTab) -> Unit = {}) = Page {
    HeroBlock(data.greeting, "Hôm nay bạn muốn làm gì?", action = { AppButton("Bắt đầu", onClick = {}, icon = AppIcons.Plus) })
    StatGridBlock(title = "Tuần này", items = data.weekStats.mapIndexed { i, s -> if (i == 2) s.copy(color = A.colors.primary) else if (i == 3) s.copy(color = A.colors.destructive) else s })
    FeatureGridBlock(title = "Lối tắt", items = listOf(
        Feature("Lịch", "Xem các buổi theo ngày", icon = AppIcons.Calendar, onClick = { open(StarterRoute.Calendar) }),
        Feature("Xu hướng", "Biểu đồ 6 tuần", icon = AppIcons.Chart, onClick = { open(StarterRoute.Trends) }),
        Feature("Tìm kiếm", "Theo tên hoặc thẻ", icon = AppIcons.Search, onClick = { goTab(StarterTab.Search) }),
        Feature("Nhắc giờ", "Đặt lịch tập", icon = AppIcons.Bell),
    ))
    ListBlock("Gần đây", hint = "mới nhất trước", items = data.activities.take(3).map { it.toRow() }.opening { open(StarterRoute.Detail(it)) })
    CtaBlock("Nối nguồn dữ liệu", "Buổi tập sẽ tự về, không cần nhập tay.", actionLabel = "Nối ngay", onAction = {})
}

@Composable
fun StarterActivitiesScreen(data: StarterData = StarterData.Dummy, onOpen: (String) -> Unit = {}) = Page {
    ScreenHeader("Hoạt động của tôi", "Các buổi đã đạp, mới nhất trước.")
    ListBlock("Tháng 10", hint = "${data.activities.size} buổi", items = data.activities.map { it.toRow() }.opening(onOpen))
    ListBlock("Tháng 9", items = emptyList(), empty = "Chưa có buổi nào")
}

@Composable
fun StarterSearchScreen(data: StarterData = StarterData.Dummy, onOpen: (String) -> Unit = {}) = Page {
    var query by remember { mutableStateOf("") }
    ScreenHeader("Tìm kiếm", "Theo tên hoặc thẻ của buổi tập.")
    AppField(label = "Từ khoá", value = query, onChange = { query = it }, placeholder = "ví dụ: đạp nhóm")
    if (query.isBlank()) {
        Section("Gần đây") { BadgesBlock(data.searchHistory) }
    } else {
        val hits = data.activities.filter { it.title.contains(query, ignoreCase = true) }.map { it.toRow() }
        if (hits.isEmpty()) EmptyState("Không thấy “$query”", message = "Thử từ khoá khác hoặc bớt chữ.") else ListBlock("Kết quả", hint = "${hits.size} buổi", items = hits.opening(onOpen))
    }
}

@Composable
fun StarterProfileScreen(data: StarterData = StarterData.Dummy) = Page {
    Inline(gap = 4) {
        Avatar(data.user.name, size = 56)
        Stack(gap = 0) { Txt(data.user.name, A.type.section.copy(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp))); Muted(data.user.email) }
    }
    StatGridBlock(title = "Chỉ số của bạn", items = data.profileStats.mapIndexed { i, s -> if (i == 2) s.copy(color = A.colors.primary) else s })
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

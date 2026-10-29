package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

/**
 * CÁC TRANG MẪU còn lại (dữ liệu giả, không logic): Đăng nhập, Đăng ký, Chi tiết, Lịch, Xu hướng. Chép trang nào cần vào app rồi nối dữ liệu thật.
 * Điều hướng giữa chúng nằm ở `StarterApp` (StarterHome.kt).
 */

@Composable
fun StarterLoginPage(data: StarterData = StarterData.Dummy, onLogin: () -> Unit = {}, onRegister: () -> Unit = {}) = Page {
    var email by remember { mutableStateOf(data.user.email) }
    var password by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Stack(Modifier.pt(10), gap = 5) {
        HeroBlock("My App", "Đăng nhập để tiếp tục.")
        // gợi ý email mẫu: data.user.email (chỉ để thấy bố cục)
        if (failed) AppAlert("Sai email hoặc mật khẩu.", destructive = true)
        AppField("Email", email, { email = it }, keyboard = KeyboardType.Email, placeholder = "ban@example.com")
        AppField("Mật khẩu", password, { password = it }, password = true)
        AppButton("Đăng nhập", onClick = { if (email.isBlank() || password.isBlank()) failed = true else onLogin() }, fullWidth = true)
        Inline(gap = 1) { Muted("Chưa có tài khoản?", A.type.body); Txt("Đăng ký", A.type.body, A.colors.primary, Modifier.onTap(onRegister)) }
    }
}

@Composable
fun StarterRegisterPage(onDone: () -> Unit = {}, onBack: () -> Unit = {}) = Page {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = again.isNotEmpty() && again != password
    Stack(Modifier.pt(10), gap = 5) {
        HeroBlock("Tạo tài khoản", "Mất chưa tới một phút.")
        AppField("Email", email, { email = it }, keyboard = KeyboardType.Email)
        AppField("Mật khẩu", password, { password = it }, password = true, hint = "Ít nhất 8 ký tự")
        AppField("Nhập lại mật khẩu", again, { again = it }, password = true)
        if (mismatch) AppAlert("Hai mật khẩu chưa khớp.", destructive = true)
        AppButton("Đăng ký", onClick = { if (!mismatch && email.isNotBlank() && password.length >= 8) onDone() }, fullWidth = true)
        Inline(gap = 1) { Muted("Đã có tài khoản?", A.type.body); Txt("Đăng nhập", A.type.body, A.colors.primary, Modifier.onTap(onBack)) }
    }
}

@Composable
fun StarterDetailPage(data: StarterData = StarterData.Dummy, title: String = data.activities.first().title) = Page {
    val activity = data.activities.firstOrNull { it.title == title } ?: data.activities.first()
    HeroBlock(activity.title, "${activity.date}/2026 · ${activity.summary}")
    StatGridBlock(title = "Chỉ số", columns = 2, items = listOf(
        StatItem("Công suất chuẩn hoá", "214 W"), StatItem("Cường độ (IF)", "0,86"),
        StatItem("TSS", activity.tss.toString(), A.colors.primary), StatItem("Nhịp tim TB", "142"),
    ))
    Section("Thời gian ở từng vùng", hint = "phút") {
        AppCard { BarChart(data.zoneMinutes, data.zoneMinutes.indices.map { "Z${it + 1}" }) }
    }
    ListBlock("Các cú ra sức", items = data.efforts.map { ListRow(it.title, it.where, trailing = it.value) })
    Section("Ghi chú của bạn") { AppField("Ghi chú", "", {}, placeholder = "Ví dụ: ngủ ít, gió ngược") ; AppButton("Lưu ghi chú", onClick = {}, variant = ButtonVariant.Outline, size = ButtonSize.Sm) }
}

@Composable
fun StarterCalendarPage(data: StarterData = StarterData.Dummy, onOpen: (String) -> Unit = {}) = Page {
    var picked by remember { mutableStateOf(29) }
    val rides = data.rides
    Inline(spread = true) {
        AppIcon(AppIcons.ArrowLeft, size = androidx.compose.ui.unit.Dp(22f), color = A.colors.mutedForeground)
        Txt("Tháng 10, 2026", A.type.section.copy(fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp)))
        AppIcon(AppIcons.ChevronRight, size = androidx.compose.ui.unit.Dp(22f), color = A.colors.mutedForeground)
    }
    Grid(columns = 7, gap = 1) { listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN").forEach { d -> cell { Muted(d, A.type.tiny) } } }
    // tháng 10/2026 bắt đầu từ thứ Năm: 3 ô trống đầu
    Grid(columns = 7, gap = 1) {
        repeat(3) { cell { } }
        for (day in 1..31) cell {
            val tss = rides[day]
            val tone = when { tss == null -> A.colors.border; tss < 50 -> A.colors.mutedForeground; tss < 90 -> A.colors.primary; tss < 130 -> A.colors.accent; else -> A.colors.destructive }
            Stack(Modifier.rounded(Radius.Sm).bordered(Radius.Sm, if (day == picked) A.colors.primary else A.colors.border).onTap { picked = day }.p(1), gap = 1) {
                Txt(day.toString(), A.type.numeric.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)), if (day == picked) A.colors.primary else A.colors.foreground)
                Box0(tone, tss != null)
            }
        }
    }
    ListBlock("Ngày $picked/10", hint = rides[picked]?.let { "$it TSS" }, empty = "Không có buổi nào",
        items = if (rides[picked] == null) emptyList() else data.activities.take(1).map { it.toRow(null).copy(trailing = "${rides[picked]} TSS", onClick = { onOpen(it.title) }, chevron = true) })
}

@Composable
private fun Box0(color: androidx.compose.ui.graphics.Color, visible: Boolean) =
    androidx.compose.foundation.layout.Box(Modifier.rounded(Radius.Full).bgColor(if (visible) color else androidx.compose.ui.graphics.Color.Transparent).height(androidx.compose.ui.unit.Dp(4f)).wFull())

@Composable
fun StarterTrendsPage(data: StarterData = StarterData.Dummy) = Page {
    var range by remember { mutableStateOf(6) }
    ScreenHeader("Xu hướng", "Thể lực, mệt mỏi và độ sung của bạn.")
    Segmented(listOf(6 to "6 tuần", 12 to "3 tháng", 26 to "6 tháng"), range, { range = it })
    AppCard { LineChart(listOf(ChartSeries("Thể lực", data.ctl, A.colors.primary), ChartSeries("Mệt", data.atl, A.colors.destructive))) }
    StatGridBlock(items = data.trendStats.mapIndexed { i, s -> if (i == 0) s.copy(color = A.colors.primary) else if (i == 1) s.copy(color = A.colors.destructive) else s })
    CtaBlock("Hỏi coach", "Xem nhận xét và kế hoạch 6 tuần tới.", actionLabel = "Hỏi coach", onAction = {})
}

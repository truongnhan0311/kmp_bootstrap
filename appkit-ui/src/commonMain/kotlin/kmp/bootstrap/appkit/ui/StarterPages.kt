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
fun StarterLoginPage(onLogin: () -> Unit = {}, onRegister: () -> Unit = {}) = Page {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    Stack(Modifier.pt(10), gap = 5) {
        HeroBlock("My App", "Đăng nhập để tiếp tục.")
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
fun StarterDetailPage(title: String = "Đạp buổi sáng") = Page {
    HeroBlock(title, "Thứ Năm 29/10/2026 · 04:26")
    StatGridBlock(title = "Chỉ số", columns = 2, items = listOf(
        StatItem("Công suất chuẩn hoá", "214 W"), StatItem("Cường độ (IF)", "0,86"),
        StatItem("TSS", "81", A.colors.primary), StatItem("Nhịp tim TB", "142"),
    ))
    Section("Thời gian ở từng vùng", hint = "phút") {
        AppCard { BarChart(listOf(8f, 31f, 22f, 9f, 3f, 0f, 0f), listOf("Z1", "Z2", "Z3", "Z4", "Z5", "Z6", "Z7")) }
    }
    ListBlock("Các cú ra sức", items = listOf(
        ListRow("Nước rút", "km 12,4 · 8 giây", trailing = "812 W"),
        ListRow("Cú giật", "km 21,0 · 45 giây", trailing = "506 W"),
    ))
    Section("Ghi chú của bạn") { AppField("Ghi chú", "", {}, placeholder = "Ví dụ: ngủ ít, gió ngược") ; AppButton("Lưu ghi chú", onClick = {}, variant = ButtonVariant.Outline, size = ButtonSize.Sm) }
}

@Composable
fun StarterCalendarPage(onOpen: (String) -> Unit = {}) = Page {
    var picked by remember { mutableStateOf(29) }
    val rides = mapOf(1 to 40, 3 to 62, 4 to 95, 6 to 70, 9 to 88, 11 to 130, 15 to 75, 17 to 145, 22 to 92, 24 to 178, 25 to 105, 28 to 40, 29 to 81)
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
        items = if (rides[picked] == null) emptyList() else listOf(ListRow("Đạp buổi sáng", "04:26 · 1h13 · 32 km", trailing = "${rides[picked]} TSS", onClick = { onOpen("Đạp buổi sáng") }, chevron = true)))
}

@Composable
private fun Box0(color: androidx.compose.ui.graphics.Color, visible: Boolean) =
    androidx.compose.foundation.layout.Box(Modifier.rounded(Radius.Full).bgColor(if (visible) color else androidx.compose.ui.graphics.Color.Transparent).height(androidx.compose.ui.unit.Dp(4f)).wFull())

@Composable
fun StarterTrendsPage() = Page {
    var range by remember { mutableStateOf(6) }
    val ctl = listOf(31f, 33f, 36f, 38f, 41f, 40f, 43f); val atl = listOf(45f, 52f, 40f, 58f, 49f, 66f, 61f)
    ScreenHeader("Xu hướng", "Thể lực, mệt mỏi và độ sung của bạn.")
    Segmented(listOf(6 to "6 tuần", 12 to "3 tháng", 26 to "6 tháng"), range, { range = it })
    AppCard { LineChart(listOf(ChartSeries("Thể lực", ctl, A.colors.primary), ChartSeries("Mệt", atl, A.colors.destructive))) }
    StatGridBlock(items = listOf(StatItem("Thể lực", "43", A.colors.primary), StatItem("Mệt", "61", A.colors.destructive), StatItem("Độ sung", "−18"), StatItem("Tải tuần này", "121")))
    CtaBlock("Hỏi coach", "Xem nhận xét và kế hoạch 6 tuần tới.", actionLabel = "Hỏi coach", onAction = {})
}

package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import kmp.bootstrap.appkit.SvgIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Khung màn hình (rút từ UI của Taper: thanh trên + nội dung cuộn + tab dưới). Ghép như dựng trang:
 *
 *     AppScaffold(
 *         topBar = { AppHeader("Hoạt động", actions = { AppButton("Đăng xuất", {}, variant = ButtonVariant.Outline, size = ButtonSize.Sm) }) },
 *         bottomBar = { TabBar(tabs, selected) { selected = it } },
 *     ) {
 *         Page { Section("Hôm nay") { AppCard { … } } }
 *     }
 */

/** Khung cả màn: [topBar] cố định, nội dung chiếm phần còn lại, [bottomBar] cố định. */
@Composable
fun AppScaffold(modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().bgBackground()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        bottomBar()
    }
}

/** Nội dung màn cuộn dọc có lề (`px-5 py-3`): đặt trong [AppScaffold]. [scroll] = false khi bên trong có danh sách cuộn riêng. */
@Composable
fun Page(modifier: Modifier = Modifier, scroll: Boolean = true, padding: Int = 5, content: @Composable ColumnScope.() -> Unit) {
    val base = if (scroll) modifier.fillMaxSize().verticalScroll(rememberScrollState()) else modifier.fillMaxSize()
    Column(base.px(padding).py(3).pb(6), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(space(4)), content = content)
}

/** Thanh trên: nút quay lại (nếu có [onBack]), [title] ở trái, [actions] ở phải. */
@Composable
fun AppHeader(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().px(4).py(2), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Txt("‹", A.type.title.copy(fontSize = 28.sp), A.colors.primary, Modifier.onTap(onBack).pr(3))
        Txt(title, A.type.section.copy(fontSize = 17.sp), if (onBack != null) A.colors.foreground else A.colors.primary)
        Box(Modifier.weight(1f))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(space(2)), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

data class TabItem<T>(val key: T, val label: String, val icon: SvgIcon? = null)

/** Thanh tab dưới (điều hướng gốc): tab đang chọn có vạch màu chính phía trên, icon và chữ đổi sang màu chính. */
@Composable
fun <T> TabBar(items: List<TabItem<T>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Row(modifier.fillMaxWidth().bgCard().py(1)) {
        items.forEach { item ->
            val active = item.key == selected
            Column(Modifier.weight(1f).onTap { onSelect(item.key) }.py(2), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 24.dp, height = 3.dp).clip(CircleShape).background(if (active) A.colors.primary else Color.Transparent))
                val tone = if (active) A.colors.primary else A.colors.mutedForeground
                if (item.icon != null) AppIcon(item.icon, Modifier.pt(1), size = 22.dp, color = tone)
                Txt(item.label, A.type.small.copy(fontSize = 11.sp), if (active) A.colors.foreground else A.colors.mutedForeground, Modifier.pt(1))
            }
        }
    }
}

/** Nhóm có tiêu đề: tiêu đề (+ chữ phụ bên phải) rồi nội dung, cách nhau đều. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(space(2))) {
        SectionTitle(title, hint)
        content()
    }
}

/** Một hàng danh sách: [leading] (ảnh/icon) · tiêu đề + phụ đề · [trailing] (giá trị/mũi tên). Có [onClick] thì bấm được. */
@Composable
fun ListItem(title: String, modifier: Modifier = Modifier, subtitle: String? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().onTap(onClick).py(2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(space(3))) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Txt(title, A.type.body, maxLines = 1)
            if (subtitle != null) Muted(subtitle, A.type.small)
        }
        trailing?.invoke()
    }
}

/** Trạng thái rỗng: tiêu đề, lời giải thích, và (tuỳ chọn) nút hành động. */
@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, message: String? = null, action: (@Composable () -> Unit)? = null) {
    Stack(modifier.fillMaxWidth().p(6), gap = 2, align = Alignment.CenterHorizontally) {
        Txt(title, A.type.section)
        if (message != null) Muted(message, A.type.body)
        action?.invoke()
    }
}

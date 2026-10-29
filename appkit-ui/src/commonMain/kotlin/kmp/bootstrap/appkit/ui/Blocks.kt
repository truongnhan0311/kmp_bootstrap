package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import kmp.bootstrap.appkit.SvgIcon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * BLOCK dựng sẵn kiểu Bootstrap: mỗi block là một khối trang hoàn chỉnh, ghép chồng lên nhau trong `Page { }`.
 * Chỉ cần đưa dữ liệu vào; muốn khác thì chép block ra rồi sửa (đều ngắn, chỉ ghép Stack/Inline/Grid/AppCard).
 *
 *     Page {
 *         HeroBlock("Chào buổi sáng", "Hôm nay bạn muốn làm gì?", action = { AppButton("Bắt đầu", {}) })
 *         StatGridBlock(listOf(StatItem("Thể lực", "43"), StatItem("Mệt", "61"), StatItem("Sung", "−18")))
 *         ListBlock("Gần đây", items = listOf(ListRow("Đạp buổi sáng", "1h13 · 32 km", trailing = "81")))
 *         CtaBlock("Nối nguồn dữ liệu", "Buổi tập sẽ tự về.", actionLabel = "Nối ngay", onAction = {})
 *     }
 */

/** Khối đầu trang: câu chào lớn, phụ đề (chữ serif) và một nút hành động. */
@Composable
fun HeroBlock(title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Stack(modifier.fillMaxWidth().py(2), gap = 2) {
        Txt(title, A.type.title)
        if (subtitle != null) Txt(subtitle, A.type.prose, A.colors.mutedForeground)
        action?.invoke()
    }
}

data class StatItem(val label: String, val value: String, val color: Color? = null)

/** Lưới ô chỉ số: [columns] cột, mỗi ô là thẻ nhỏ nhãn + số (chữ mono). */
@Composable
fun StatGridBlock(items: List<StatItem>, modifier: Modifier = Modifier, columns: Int = 2, title: String? = null) {
    Section(title.orEmpty(), modifier) {
        Grid(columns = columns, gap = 3) {
            items.forEach { item -> cell { AppCard(padding = androidx.compose.foundation.layout.PaddingValues(space(3))) { Stat(item.label, item.value, color = item.color ?: A.colors.foreground) } } }
        }
    }
}

data class ListRow(val title: String, val subtitle: String? = null, val trailing: String? = null, val onClick: (() -> Unit)? = null, val icon: SvgIcon? = null, val leading: (@Composable () -> Unit)? = null, val chevron: Boolean = false)

/** Danh sách trong một thẻ, các hàng ngăn bằng đường kẻ. Rỗng thì hiện [empty]. */
@Composable
fun ListBlock(title: String, items: List<ListRow>, modifier: Modifier = Modifier, hint: String? = null, empty: String = "—") {
    Section(title, modifier, hint) {
        AppCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = space(4), vertical = space(1))) {
            if (items.isEmpty()) EmptyState(empty)
            items.forEachIndexed { i, row ->
                if (i > 0) Divider()
                ListItem(row.title, subtitle = row.subtitle, onClick = row.onClick,
                    leading = row.leading ?: row.icon?.let { k -> { AppIcon(k, size = 20.dp, color = A.colors.mutedForeground) } },
                    trailing = if (row.trailing != null || row.chevron) ({
                        Inline(gap = 1) {
                            row.trailing?.let { Txt(it, A.type.numeric, A.colors.mutedForeground) }
                            if (row.chevron) AppIcon(AppIcons.ChevronRight, size = 16.dp, color = A.colors.mutedForeground)
                        }
                    }) else null)
            }
        }
    }
}

data class Feature(val title: String, val description: String, val onClick: (() -> Unit)? = null, val icon: SvgIcon? = null)

/** Lưới thẻ tính năng / lối tắt: tiêu đề + mô tả ngắn, bấm được. */
@Composable
fun FeatureGridBlock(items: List<Feature>, modifier: Modifier = Modifier, columns: Int = 2, title: String? = null) {
    Section(title.orEmpty(), modifier) {
        Grid(columns = columns, gap = 3) {
            items.forEach { f -> cell { AppCard(onClick = f.onClick, padding = androidx.compose.foundation.layout.PaddingValues(space(3))) { if (f.icon != null) AppIcon(f.icon, Modifier.pb(2), size = 22.dp, color = A.colors.primary); Txt(f.title, A.type.section); Muted(f.description) } } }
        }
    }
}

/** Khối kêu gọi hành động: thẻ nổi bật viền màu chính, một câu và một nút. */
@Composable
fun CtaBlock(title: String, message: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier, outline = A.colors.primary) {
        Stack(gap = 2) {
            Txt(title, A.type.section)
            Muted(message, A.type.body)
            AppButton(actionLabel, onAction, size = ButtonSize.Sm)
        }
    }
}

/** Hàng nhãn nhỏ (chip) tự xuống dòng. */
@Composable
fun BadgesBlock(labels: List<String>, modifier: Modifier = Modifier) = Wrap(modifier) { labels.forEach { Badge(it) } }

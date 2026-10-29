package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Bộ layout kiểu Tailwind/Bootstrap để dựng màn như viết TSX:
 *
 *     React (TSX)                               Compose (kit này)
 *     <div className="flex flex-col gap-3">     Stack(gap = 3) { … }
 *     <div className="flex items-center gap-2"> Inline(gap = 2) { … }
 *     <div className="grid grid-cols-2 gap-3">  Grid(columns = 2, gap = 3) { cell { … }; cell { … } }
 *     className="p-4 rounded-lg border bg-card" Modifier.p(4).cardStyle()
 *     <Button variant="outline" size="sm">      AppButton("Lưu", onClick, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
 *
 * Thang khoảng cách: 1 đơn vị = 4dp (như Tailwind) → `p(4)` = 16dp, `gap = 3` = 12dp.
 * Mọi màu lấy từ token theme (A.colors) nên đổi theme là đổi tất cả — không có màu cứng ở đây.
 */

/** Đơn vị khoảng cách: n × 4dp. */
fun space(n: Int): Dp = (n * 4).dp

// ---- tiện ích Modifier (như className) ----
fun Modifier.p(n: Int) = padding(space(n))
fun Modifier.px(n: Int) = padding(horizontal = space(n))
fun Modifier.py(n: Int) = padding(vertical = space(n))
fun Modifier.pt(n: Int) = padding(top = space(n))
fun Modifier.pb(n: Int) = padding(bottom = space(n))
fun Modifier.pl(n: Int) = padding(start = space(n))
fun Modifier.pr(n: Int) = padding(end = space(n))
fun Modifier.wFull() = fillMaxWidth()
fun Modifier.onTap(onClick: (() -> Unit)?) = if (onClick != null) clickable(onClick = onClick) else this

/** Bo góc theo thang: Sm 6 · Md 10 · Lg 14 · Xl 20 · Full tròn. */
enum class Radius(val dp: Dp) { None(0.dp), Sm(6.dp), Md(10.dp), Lg(14.dp), Xl(20.dp), Full(999.dp) }
fun Modifier.rounded(radius: Radius = Radius.Md) = clip(RoundedCornerShape(radius.dp))

@Composable fun Modifier.bgBackground() = background(A.colors.background)
@Composable fun Modifier.bgCard() = background(A.colors.card)
@Composable fun Modifier.bgMuted() = background(A.colors.muted)
@Composable fun Modifier.bgPrimary() = background(A.colors.primary)
@Composable fun Modifier.bgColor(color: Color) = background(color)

/** Viền mảnh màu `border` (hoặc màu bạn chọn), bo theo [radius]. */
@Composable fun Modifier.bordered(radius: Radius = Radius.Md, color: Color = A.colors.border, width: Dp = 1.dp) = border(BorderStroke(width, color), RoundedCornerShape(radius.dp))

/** Kiểu thẻ shadcn: nền `card`, viền `border`, bo [radius] — dùng cho bất kỳ khối nào muốn trông như thẻ. */
@Composable fun Modifier.cardStyle(radius: Radius = Radius.Lg) = rounded(radius).bgCard().bordered(radius)

// ---- khối bố cục ----

/** `flex flex-col gap-N`: xếp dọc, cách nhau [gap]. */
@Composable
fun Stack(modifier: Modifier = Modifier, gap: Int = 3, align: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier, verticalArrangement = Arrangement.spacedBy(space(gap)), horizontalAlignment = align, content = content)

/** `flex items-center gap-N`: xếp ngang, mặc định căn giữa theo chiều dọc. [spread] = `justify-between`. */
@Composable
fun Inline(modifier: Modifier = Modifier, gap: Int = 2, align: Alignment.Vertical = Alignment.CenterVertically, spread: Boolean = false, content: @Composable RowScope.() -> Unit) =
    Row(modifier, horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(space(gap)), verticalAlignment = align, content = content)

/** `flex flex-wrap gap-N`: xếp ngang và xuống dòng khi hết chỗ (nhãn, chip). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Wrap(modifier: Modifier = Modifier, gap: Int = 2, content: @Composable () -> Unit) =
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(space(gap)), verticalArrangement = Arrangement.spacedBy(space(gap))) { content() }

class GridScope { internal val cells = mutableListOf<@Composable () -> Unit>(); fun cell(content: @Composable () -> Unit) { cells += content } }

/** `grid grid-cols-N gap-M`: lưới [columns] cột chia đều; hàng cuối thiếu ô thì chừa trống. */
@Composable
fun Grid(columns: Int, modifier: Modifier = Modifier, gap: Int = 3, content: GridScope.() -> Unit) {
    require(columns >= 1) { "columns must be >= 1" }
    val cells = GridScope().apply(content).cells
    Column(modifier, verticalArrangement = Arrangement.spacedBy(space(gap))) {
        cells.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(space(gap))) {
                row.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Căn giữa nội dung trong ô cha. */
@Composable fun Center(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier, contentAlignment = Alignment.Center) { content() }

/** Giới hạn bề rộng và căn giữa (máy tính bảng / desktop): nội dung không bị kéo giãn quá [maxWidth]. */
@Composable
fun Container(modifier: Modifier = Modifier, maxWidth: Dp = 640.dp, content: @Composable ColumnScope.() -> Unit) =
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { Column(modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content) }

/** Đường kẻ ngang mảnh. */
@Composable fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(A.colors.border))

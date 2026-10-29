package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kmp.bootstrap.appkit.SvgIcon
import kmp.bootstrap.appkit.SvgParser

/*
 * Vẽ icon SVG bằng một màu của theme (như `stroke="currentColor"` trên web):
 *
 *     AppIcon(AppIcons.Home)                                          // 20dp, màu chữ
 *     AppIcon(AppIcons.Search, size = 24.dp, color = A.colors.primary)
 *     AppIcon(SvgParser.parse(svgString))                             // SVG bất kỳ lấy lúc chạy (từ server, từ cấu hình)
 *     AppButton("Thêm", onClick, icon = AppIcons.Plus)                // nút có icon
 *     TabItem("home", "Trang chủ", AppIcons.Home)                     // tab có icon
 *
 * Thêm icon: thả file .svg vào appkit-ui/icons (hoặc thư mục icon của app bạn) rồi chạy tools/svg-to-kotlin.mjs — xem docs/ui.md.
 */
@Composable
fun AppIcon(icon: SvgIcon, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = A.colors.foreground, strokeWidth: Float? = null) {
    // Parse path một lần cho mỗi icon (PathParser tốn hơn việc vẽ lại).
    val paths = remember(icon) { icon.elements.map { e -> PathParser().parsePathString(e.pathData).toPath().also { it.fillType = if (e.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero } } }
    Canvas(modifier.size(size)) {
        val s = this.size.width / icon.viewBox
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            icon.elements.forEachIndexed { i, e ->
                if (e.fill) drawPath(paths[i], color, style = Fill)
                if (e.stroke) drawPath(paths[i], color, style = Stroke(
                    width = strokeWidth ?: e.strokeWidth,
                    cap = if (e.round) StrokeCap.Round else StrokeCap.Butt, join = if (e.round) StrokeJoin.Round else StrokeJoin.Miter,
                ))
            }
        }
    }
}

/** Dựng icon từ chuỗi SVG (đã nhớ kết quả theo chuỗi). */
@Composable
fun AppIcon(svg: String, modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = A.colors.foreground) =
    AppIcon(remember(svg) { SvgParser.parse(svg) }, modifier, size, color)

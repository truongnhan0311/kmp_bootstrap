package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Hai biểu đồ đơn giản cho trang mẫu (cột và đường), vẽ bằng Canvas, màu theo theme. Đủ để dựng khung trang; biểu đồ phức tạp
 * (zoom, dò giá trị…) tự viết thêm trong app. Mọi con số do code/máy chủ tính sẵn — biểu đồ chỉ vẽ, không tính.
 *
 *     BarChart(listOf(12f, 30f, 8f), labels = listOf("Z1", "Z2", "Z3"))
 *     LineChart(listOf(ChartSeries("Thể lực", ctl, A.colors.primary), ChartSeries("Mệt", atl, A.colors.destructive)))
 */
data class ChartSeries(val name: String, val values: List<Float>, val color: Color)

@Composable
fun BarChart(values: List<Float>, labels: List<String>, modifier: Modifier = Modifier, height: Dp = 120.dp, color: Color = A.colors.primary) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    val line = A.colors.border
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val slot = size.width / values.size.coerceAtLeast(1)
            val bar = slot * 0.6f
            values.forEachIndexed { i, v ->
                val h = size.height * (v / max)
                drawRect(color, Offset(i * slot + (slot - bar) / 2, size.height - h), Size(bar, h))
            }
            drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        }
        Row(Modifier.fillMaxWidth().pt(1)) { labels.forEach { Muted(it, A.type.tiny, Modifier.weight(1f)) } }
    }
}

@Composable
fun LineChart(series: List<ChartSeries>, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    val grid = A.colors.border
    val all = series.flatMap { it.values }
    val lo = (all.minOrNull() ?: 0f).coerceAtMost(0f); val hi = (all.maxOrNull() ?: 1f).coerceAtLeast(lo + 1f)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            repeat(4) { i -> val y = size.height * i / 3f; drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f) }
            series.forEach { s ->
                if (s.values.size < 2) return@forEach
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val x = size.width * i / (s.values.size - 1)
                    val y = size.height * (1f - (v - lo) / (hi - lo))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Inline(Modifier.pt(2), gap = 4) {
            series.forEach { s -> Inline(gap = 1) { androidx.compose.foundation.layout.Box(Modifier.size(8.dp).rounded(Radius.Full).bgColor(s.color)); Muted(s.name, A.type.tiny) } }
        }
    }
}

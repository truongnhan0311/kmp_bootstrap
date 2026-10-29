package kmp.bootstrap.appkit

/*
 * Đọc SVG thành dữ liệu để vẽ (không phụ thuộc Compose nên test được trên JVM). Hỗ trợ đúng phần icon cần:
 * <svg viewBox fill stroke stroke-width …> chứa path, line, polyline, polygon, circle, ellipse, rect (kể cả bo góc rx/ry).
 * Mọi hình được đổi thành đường path ("d") để một bộ vẽ duy nhất xử lý. Không hỗ trợ: gradient, transform, <g transform>, text, style CSS —
 * gặp thì bỏ qua phần đó (không ném lỗi). Icon kiểu Lucide / Heroicons / Feather / Tabler đều nằm trong phạm vi này.
 *
 *     val icon = SvgParser.parse("""<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"><path d="M5 12h14"/></svg>""")
 */
data class SvgElement(
    val pathData: String,
    val fill: Boolean,
    val stroke: Boolean,
    val strokeWidth: Float,
    val round: Boolean,
    val evenOdd: Boolean = false,
)

data class SvgIcon(val viewBox: Float, val elements: List<SvgElement>)

object SvgParser {
    private val tag = Regex("<(path|line|polyline|polygon|circle|ellipse|rect)\\b([^>]*?)/?>", RegexOption.IGNORE_CASE)
    private val attr = Regex("([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
    private val num = Regex("-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun parse(svg: String): SvgIcon {
        val root = Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE).find(svg)?.groupValues?.get(1).orEmpty().let(::attrs)
        val viewBox = root["viewBox"]?.let { nums(it) }?.takeIf { it.size == 4 }?.let { maxOf(it[2], it[3]) }
            ?: root["width"]?.let { nums(it).firstOrNull() } ?: 24f
        val elements = tag.findAll(svg).mapNotNull { m ->
            val a = attrs(m.groupValues[2])
            val d = shapeToPath(m.groupValues[1].lowercase(), a) ?: return@mapNotNull null
            // Quy tắc SVG: không khai fill thì tô đen (currentColor); không khai stroke thì không viền.
            val fill = (a["fill"] ?: root["fill"] ?: "currentColor") != "none"
            val strokeRaw = a["stroke"] ?: root["stroke"]
            SvgElement(
                pathData = d, fill = fill, stroke = strokeRaw != null && strokeRaw != "none",
                strokeWidth = (a["stroke-width"] ?: root["stroke-width"])?.let { nums(it).firstOrNull() } ?: 1f,
                round = (a["stroke-linecap"] ?: root["stroke-linecap"]) == "round" || (a["stroke-linejoin"] ?: root["stroke-linejoin"]) == "round",
                evenOdd = (a["fill-rule"] ?: root["fill-rule"]) == "evenodd",
            )
        }.toList()

        return SvgIcon(viewBox, elements)
    }

    private fun attrs(s: String): Map<String, String> = attr.findAll(s).associate { it.groupValues[1] to (it.groupValues[2].ifEmpty { it.groupValues[3] }) }
    private fun nums(s: String): List<Float> = num.findAll(s).mapNotNull { it.value.toFloatOrNull() }.toList()
    private fun f(a: Map<String, String>, k: String, default: Float = 0f) = a[k]?.let { nums(it).firstOrNull() } ?: default
    private fun n(x: Float): String = if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString()

    private fun shapeToPath(kind: String, a: Map<String, String>): String? = when (kind) {
        "path" -> a["d"]?.takeIf { it.isNotBlank() }
        "line" -> "M${n(f(a, "x1"))} ${n(f(a, "y1"))}L${n(f(a, "x2"))} ${n(f(a, "y2"))}"
        "polyline", "polygon" -> nums(a["points"].orEmpty()).chunked(2).filter { it.size == 2 }.takeIf { it.size >= 2 }?.let { pts ->
            pts.mapIndexed { i, p -> (if (i == 0) "M" else "L") + "${n(p[0])} ${n(p[1])}" }.joinToString("") + if (kind == "polygon") "Z" else ""
        }
        "circle" -> f(a, "r").takeIf { it > 0 }?.let { r -> ellipse(f(a, "cx"), f(a, "cy"), r, r) }
        "ellipse" -> { val rx = f(a, "rx"); val ry = f(a, "ry"); if (rx > 0 && ry > 0) ellipse(f(a, "cx"), f(a, "cy"), rx, ry) else null }
        "rect" -> {
            val x = f(a, "x"); val y = f(a, "y"); val w = f(a, "width"); val h = f(a, "height")
            val rx = minOf(if (a.containsKey("rx")) f(a, "rx") else f(a, "ry"), w / 2); val ry = minOf(if (a.containsKey("ry")) f(a, "ry") else rx, h / 2)
            if (w <= 0 || h <= 0) null
            else if (rx <= 0 || ry <= 0) "M${n(x)} ${n(y)}H${n(x + w)}V${n(y + h)}H${n(x)}Z"
            else "M${n(x + rx)} ${n(y)}H${n(x + w - rx)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + w)} ${n(y + ry)}V${n(y + h - ry)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + w - rx)} ${n(y + h)}" +
                "H${n(x + rx)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x)} ${n(y + h - ry)}V${n(y + ry)}A${n(rx)} ${n(ry)} 0 0 1 ${n(x + rx)} ${n(y)}Z"
        }
        else -> null
    }

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
        "M${n(cx - rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx + rx)} ${n(cy)}A${n(rx)} ${n(ry)} 0 1 0 ${n(cx - rx)} ${n(cy)}Z"
}

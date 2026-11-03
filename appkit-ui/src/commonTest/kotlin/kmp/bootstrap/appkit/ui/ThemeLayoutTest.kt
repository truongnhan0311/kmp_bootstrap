package kmp.bootstrap.appkit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThemeLayoutTest {
    private fun colors(dark: Boolean, extra: Map<String, Color> = emptyMap()): AppColors {
        fun c(n: Int) = Color(0xFF000000 or (n.toLong() * 0x010101))
        return AppColors(dark, c(1), c(2), c(3), c(4), c(5), c(6), c(7), c(8), c(9), c(10), c(11), c(12), c(13), c(14), c(15), c(16), c(17), c(18), c(19), extra)
    }

    @Test fun spacingScaleIsFourDpPerUnit() {
        assertEquals(0.dp, space(0)); assertEquals(4.dp, space(1)); assertEquals(12.dp, space(3)); assertEquals(16.dp, space(4))
    }

    @Test fun radiusScaleIsOrderedAndFullIsPill() {
        val r = Radius.entries.map { it.dp.value }
        assertEquals(r.sorted(), r, "thang bo góc tăng dần")
        assertTrue(Radius.Full.dp > 100.dp)
    }

    @Test fun extraTokenIsReturnedOrFailsLoudly() {
        val red = Color(0xFFFF0000)
        val c = colors(false, mapOf("fitness" to red))
        assertEquals(red, c.extra("fitness"))
        val e = assertFailsWith<IllegalStateException> { c.extra("không-có") }
        assertTrue(e.message!!.contains("không-có"), "thông báo nêu tên token thiếu")
    }

    @Test fun materialSchemeFollowsTheTokensAndTheDarkFlag() {
        val light = colors(false).toMaterial()
        val dark = colors(true).toMaterial()
        val src = colors(false)
        assertEquals(src.primary, light.primary); assertEquals(src.primaryForeground, light.onPrimary)
        assertEquals(src.background, light.background); assertEquals(src.card, light.surface); assertEquals(src.destructive, light.error)
        assertEquals(src.border, light.outline); assertEquals(src.muted, light.surfaceVariant)
        assertEquals(colors(true).background, dark.background)
        assertTrue(light != dark, "sáng và tối là hai bảng màu khác nhau")
    }

    @Test fun starterActivityBecomesARowWithTssAsTrailing() {
        val row = StarterActivity("Đạp sáng", "Thứ 2", "1h13 · 32 km", 81).toRow()
        assertEquals("Đạp sáng", row.title); assertEquals("Thứ 2 · 1h13 · 32 km", row.subtitle); assertEquals("81 TSS", row.trailing)
    }
}

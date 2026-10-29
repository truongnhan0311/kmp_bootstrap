package kmp.bootstrap.appkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgTest {
    private val lucide = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">"""

    @Test fun lucideStyleRootAttributesAreInheritedByEveryShape() {
        val icon = SvgParser.parse("""$lucide<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg>""")
        assertEquals(24f, icon.viewBox); assertEquals(2, icon.elements.size)
        icon.elements.forEach { assertFalse(it.fill); assertTrue(it.stroke); assertEquals(2f, it.strokeWidth); assertTrue(it.round) }
        assertEquals("M5 12h14", icon.elements[0].pathData)
    }

    @Test fun elementAttributesOverrideTheRoot() {
        val icon = SvgParser.parse("""$lucide<circle cx="12" cy="12" r="2" fill="currentColor" stroke-width="3"/></svg>""")
        assertTrue(icon.elements.single().fill); assertEquals(3f, icon.elements.single().strokeWidth)
    }

    @Test fun svgSpecDefaultsApplyWhenNothingIsDeclared() {
        val e = SvgParser.parse("""<svg viewBox="0 0 16 16"><path d="M0 0h4v4z"/></svg>""").elements.single()
        assertTrue(e.fill, "không khai fill = tô"); assertFalse(e.stroke, "không khai stroke = không viền")
        assertEquals(16f, SvgParser.parse("""<svg viewBox="0 0 16 16"/>""").viewBox)
    }

    @Test fun basicShapesBecomePaths() {
        val svg = """$lucide<line x1="4" y1="6" x2="20" y2="6"/><polyline points="9 5 16 12 9 19"/><polygon points="0,0 4,0 4,4"/>
            <circle cx="10" cy="10" r="3"/><rect x="4" y="5" width="16" height="15"/><rect x="4" y="5" width="16" height="15" rx="2.5"/><ellipse cx="5" cy="5" rx="2" ry="1"/></svg>"""
        val d = SvgParser.parse(svg).elements.map { it.pathData }
        assertEquals("M4 6L20 6", d[0]); assertEquals("M9 5L16 12L9 19", d[1]); assertEquals("M0 0L4 0L4 4Z", d[2])
        assertEquals("M7 10A3 3 0 1 0 13 10A3 3 0 1 0 7 10Z", d[3]); assertEquals("M4 5H20V20H4Z", d[4])
        assertTrue(d[5].contains("A2.5 2.5 0 0 1") && d[5].startsWith("M6.5 5H17.5")); assertEquals("M3 5A2 1 0 1 0 7 5A2 1 0 1 0 3 5Z", d[6])
    }

    @Test fun unsupportedOrBrokenInputIsIgnoredNotFatal() {
        assertTrue(SvgParser.parse("không phải svg").elements.isEmpty())
        val icon = SvgParser.parse("""$lucide<text>x</text><circle r="0"/><polyline points="1 2"/><path d=""/><path d="M0 0h1"/></svg>""")
        assertEquals(listOf("M0 0h1"), icon.elements.map { it.pathData })
    }

    @Test fun singleQuotedAttributesAndDecimalsWork() {
        val e = SvgParser.parse("""<svg viewBox='0 0 24 24' fill='none' stroke='#000' stroke-width='1.5'><path d='M1.5 2'/></svg>""").elements.single()
        assertEquals(1.5f, e.strokeWidth); assertTrue(e.stroke)
    }
}

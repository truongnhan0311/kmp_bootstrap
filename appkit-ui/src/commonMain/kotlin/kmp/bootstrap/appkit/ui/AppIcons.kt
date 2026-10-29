// SINH TỰ ĐỘNG bởi tools/svg-to-kotlin.mjs từ icons/*.svg — đừng sửa tay: thêm/sửa file .svg rồi chạy lại script.
package kmp.bootstrap.appkit.ui

import kmp.bootstrap.appkit.SvgIcon
import kmp.bootstrap.appkit.SvgParser

object AppIcons {
    val ArrowLeft: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12H5M11 6l-6 6 6 6"/></svg>""") }
    val Bell: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 17h14l-1.5-2v-4.5C17.5 7 15 5 12 5S6.5 7 6.5 10.5V15z"/><path d="M10 20c1 1.4 3 1.4 4 0"/></svg>""") }
    val Calendar: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="5" width="16" height="15" rx="2.5"/><path d="M4 10h16M8 3v4M16 3v4"/></svg>""") }
    val Chart: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4v16h16"/><path d="m7.5 15 3.5-4 3 2.5 5-6.5"/></svg>""") }
    val Check: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m5 12.5 5 5L19 7"/></svg>""") }
    val ChevronRight: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="m9 5 7 7-7 7"/></svg>""") }
    val Home: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 10.5 12 3.5l8 7V20h-5.5v-6h-5v6H4z"/></svg>""") }
    val List: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6h11M9 12h11M9 18h11"/><circle cx="4.8" cy="6" r="1"/><circle cx="4.8" cy="12" r="1"/><circle cx="4.8" cy="18" r="1"/></svg>""") }
    val Plus: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>""") }
    val Search: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="10.5" cy="10.5" r="6.5"/><path d="m15.4 15.4 5.1 5.1"/></svg>""") }
    val Settings: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h16M4 12h16M4 18h16"/><circle cx="15" cy="6" r="2.3" fill="currentColor"/><circle cx="8.5" cy="12" r="2.3" fill="currentColor"/><circle cx="16" cy="18" r="2.3" fill="currentColor"/></svg>""") }
    val Sun: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/></svg>""") }
    val User: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c0-4 3-6 7.5-6s7.5 2 7.5 6"/></svg>""") }
    val X: SvgIcon by lazy { SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M18 6 6 18"/></svg>""") }

    /** Mọi icon theo tên file (ví dụ "chevron-right") — để chọn icon theo chuỗi (cấu hình, dữ liệu từ server). */
    val all: Map<String, SvgIcon> get() = mapOf("arrow-left" to ArrowLeft, "bell" to Bell, "calendar" to Calendar, "chart" to Chart, "check" to Check, "chevron-right" to ChevronRight, "home" to Home, "list" to List, "plus" to Plus, "search" to Search, "settings" to Settings, "sun" to Sun, "user" to User, "x" to X)
}

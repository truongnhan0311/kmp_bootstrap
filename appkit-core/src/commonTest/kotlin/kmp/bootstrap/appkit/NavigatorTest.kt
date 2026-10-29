package kmp.bootstrap.appkit

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private sealed interface R {
    data object Home : R; data object Calendar : R; data class Detail(val id: String) : R; data class Public(val token: String) : R
}

class NavigatorTest {
    @Test fun pushBackAndRootBehaviour() {
        val nav = Navigator<R>(R.Home)
        assertFalse(nav.canGoBack); assertFalse(nav.back(), "ở gốc: để hệ điều hành xử lý nút lùi")
        nav.push(R.Detail("1")); nav.push(R.Calendar)
        assertEquals(listOf(R.Home, R.Detail("1"), R.Calendar), nav.stack.value); assertTrue(nav.back()); assertEquals(R.Detail("1"), nav.current)
    }

    @Test fun pushingTheSameRouteTwiceDoesNotStack() {
        val nav = Navigator<R>(R.Home); nav.push(R.Detail("1")); nav.push(R.Detail("1"))
        assertEquals(2, nav.stack.value.size, "bấm đúp không mở hai lần"); nav.push(R.Detail("2")); assertEquals(3, nav.stack.value.size)
    }

    @Test fun resetReplaceAndPopTo() {
        val nav = Navigator<R>(R.Home); nav.push(R.Detail("1")); nav.push(R.Calendar)
        nav.replace(R.Detail("9")); assertEquals(listOf(R.Home, R.Detail("1"), R.Detail("9")), nav.stack.value)
        assertTrue(nav.popTo { it is R.Detail && it.id == "1" }); assertEquals(R.Detail("1"), nav.current)
        assertFalse(nav.popTo { it == R.Calendar }, "không có thì không đổi gì"); assertEquals(2, nav.stack.value.size)
        nav.reset(R.Calendar); assertEquals(listOf(R.Calendar), nav.stack.value); assertFalse(nav.canGoBack)
    }
}

class DeepLinksTest {
    private val links = DeepLinks<R> {
        route("/s/{token}") { R.Public(it.getValue("token")) }
        route("/activities/{id}") { R.Detail(it.getValue("id")) }
        route("/calendar") { R.Calendar }
    }

    @Test fun matchesBySegmentIgnoringSchemeHostQueryAndSlashes() {
        assertEquals(R.Public("abc"), links.resolve("https://app.example.com/s/abc?x=1#top"))
        assertEquals(R.Public("abc"), links.resolve("myapp://s/abc"))
        assertEquals(R.Detail("42"), links.resolve("/activities/42/")); assertEquals(R.Calendar, links.resolve("https://x.test/calendar"))
    }

    @Test fun nonMatchingLinksReturnNull() {
        assertNull(links.resolve("https://x.test/s/a/b"), "/s/{token} không khớp /s/a/b")
        assertNull(links.resolve("https://x.test/unknown")); assertNull(links.resolve("")); assertNull(links.resolve("https://x.test"))
    }

    @Test fun aRuleMayDeclineAndTheNextOneIsTried() {
        val l = DeepLinks<R> { route("/a/{id}") { if (it["id"] == "no") null else R.Detail(it.getValue("id")) }; route("/a/{x}") { R.Calendar } }
        assertEquals(R.Detail("1"), l.resolve("/a/1")); assertEquals(R.Calendar, l.resolve("/a/no"))
    }
}

class AppSettingsTest {
    @AfterTest fun reset() = Language.reset()

    @Test fun themeAndLanguageSurviveRestart() {
        val prefs = MemoryKeyValueStore()
        AppSettings(prefs).apply { setTheme(ThemeMode.Dark); setLanguage("vi") }
        Language.reset()
        val again = AppSettings(prefs)
        assertEquals(ThemeMode.Dark, again.theme.value); assertEquals("vi", again.language.value); assertEquals("vi", Language.current, "mở lại app là ngôn ngữ đã chọn")
    }

    @Test fun emptyLanguageMeansFollowTheDevice() {
        val s = AppSettings(MemoryKeyValueStore()); s.setLanguage("zh"); assertEquals("zh", Language.current)
        s.setLanguage(""); assertEquals(Language.normalise(systemLanguage()), Language.current)
    }

    @Test fun accountWinsOnlyForFieldsTheDeviceHasNotChanged() {
        val s = AppSettings(MemoryKeyValueStore())
        s.setTheme(ThemeMode.Light)                          // đổi trên máy, chưa lên tài khoản
        val deviceWins = s.applyAccount(theme = "dark", language = "es")
        assertEquals(ThemeMode.Light, s.theme.value, "lựa chọn gần nhất của người dùng thắng"); assertEquals(setOf("theme"), deviceWins)
        assertEquals("es", s.language.value, "ngôn ngữ chưa đổi trên máy → lấy của tài khoản")
        s.markSynced(); assertTrue(s.unsyncedChanges.isEmpty())
        s.applyAccount(theme = "dark", language = null); assertEquals(ThemeMode.Dark, s.theme.value)
    }

    @Test fun garbageInStorageFallsBackToSystem() {
        assertEquals(ThemeMode.System, AppSettings(MemoryKeyValueStore().also { it.put("settings.theme", "neon") }).theme.value)
    }
}

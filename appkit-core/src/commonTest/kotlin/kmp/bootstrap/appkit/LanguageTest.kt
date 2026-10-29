package kmp.bootstrap.appkit

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LanguageTest {
    @AfterTest fun reset() = Language.reset()

    @Test fun languageCodesAreNormalised() {
        assertEquals("vi", Language.normalise("vi-VN")); assertEquals("zh", Language.normalise("zh_Hans_CN"))
        assertEquals("en", Language.normalise("EN")); assertEquals("en", Language.normalise(""))
    }

    @Test fun explicitLanguageWinsAndUnknownFallsBackToEnglish() {
        Language.current = "vi"; assertTrue(Language.text(Msg.Timeout).contains("Máy chủ"))
        Language.current = "fr"; assertEquals("The server took too long to respond.", Language.text(Msg.Timeout), "chưa có bản dịch fr → tiếng Anh")
        Language.current = "es-MX"; assertTrue(Language.text(Msg.Timeout).contains("servidor"))
    }

    @Test fun withoutAnOverrideItFollowsTheSystemLanguage() {
        Language.current = "vi"; Language.reset()
        assertEquals(Language.normalise(systemLanguage()), Language.current)
    }

    @Test fun everyLanguageHasEveryMessageSoNothingFallsThroughByAccident() {
        for (lang in listOf("en", "vi", "es", "zh")) {
            Language.current = lang
            Msg.entries.forEach { assertTrue(Language.text(it).isNotBlank(), "$lang/$it") }
            if (lang != "en") Msg.entries.forEach { m -> Language.current = "en"; val en = Language.text(m); Language.current = lang; assertNotEquals(en, Language.text(m), "$lang/$m chưa dịch") }
        }
    }

    @Test fun registerAddsALanguageAndOverridesOneMessage() {
        Language.register("fr", mapOf(Msg.Timeout to "Délai dépassé")); Language.current = "fr"
        assertEquals("Délai dépassé", Language.text(Msg.Timeout))
        assertEquals("Not found.", Language.text(Msg.NotFound), "khoá thiếu rơi về tiếng Anh")
    }
}

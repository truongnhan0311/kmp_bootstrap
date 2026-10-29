package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StringsTest {
    @AfterTest fun reset() = Strings.reset()

    @Test fun languageCodesAreNormalised() {
        assertEquals("vi", Strings.normaliseLanguage("vi-VN")); assertEquals("zh", Strings.normaliseLanguage("zh_Hans_CN"))
        assertEquals("en", Strings.normaliseLanguage("EN")); assertEquals("en", Strings.normaliseLanguage(""))
    }

    @Test fun explicitLanguageWinsAndUnknownFallsBackToEnglish() {
        Strings.language = "vi"; assertTrue(Strings.text(Msg.Timeout).contains("Máy chủ"))
        Strings.language = "fr"; assertEquals("The server took too long to respond.", Strings.text(Msg.Timeout), "chưa có bản dịch fr → tiếng Anh")
        Strings.language = "es-MX"; assertTrue(Strings.text(Msg.Timeout).contains("servidor"))
    }

    @Test fun withoutAnOverrideItFollowsTheSystemLanguage() {
        Strings.language = "vi"; Strings.reset()
        assertEquals(Strings.normaliseLanguage(systemLanguage()), Strings.language)
    }

    @Test fun everyLanguageHasEveryMessageSoNothingFallsThroughByAccident() {
        for (lang in listOf("en", "vi", "es", "zh")) {
            Strings.language = lang
            Msg.entries.forEach { assertTrue(Strings.text(it).isNotBlank(), "$lang/$it") }
            if (lang != "en") Msg.entries.forEach { m -> Strings.language = "en"; val en = Strings.text(m); Strings.language = lang; assertNotEquals(en, Strings.text(m), "$lang/$m chưa dịch") }
        }
    }

    @Test fun registerAddsALanguageAndOverridesOneMessage() {
        Strings.register("fr", mapOf(Msg.Timeout to "Délai dépassé")); Strings.language = "fr"
        assertEquals("Délai dépassé", Strings.text(Msg.Timeout))
        assertEquals("Not found.", Strings.text(Msg.NotFound), "khoá thiếu rơi về tiếng Anh")
    }

    @Test fun apiErrorsSpeakTheCurrentLanguageButServerMessagesAreKept() = runTest {
        fun client(status: HttpStatusCode, body: String) = AuthedClient(HttpTransport("https://x.test", "a", MockEngine { respond(body, status) }, getRetries = 0), MemoryTokenStore("t", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        Strings.language = "vi"
        assertTrue(assertFailsWith<ApiError> { client(HttpStatusCode.NotFound, "{}").get<Map<String, String>>("/x") }.message.contains("Không tìm thấy"))
        Strings.language = "en"
        assertEquals("Not found.", assertFailsWith<ApiError> { client(HttpStatusCode.NotFound, "{}").get<Map<String, String>>("/x") }.message)
        assertEquals("Email taken", assertFailsWith<ApiError> { client(HttpStatusCode.UnprocessableEntity, """{"message":"Email taken"}""").get<Map<String, String>>("/x") }.message, "lời của server giữ nguyên")
        val down = AuthedClient(HttpTransport("https://x.test", "a", MockEngine { throw RuntimeException("Unable to resolve host") }, getRetries = 0), MemoryTokenStore(), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        assertEquals("Can't reach the server. Check your connection.", assertFailsWith<ApiError> { down.get<Map<String, String>>("/x") }.message)
        Strings.language = "zh"
        assertTrue(assertFailsWith<ApiError> { down.get<Map<String, String>>("/x") }.message.contains("无法连接"))
    }
}

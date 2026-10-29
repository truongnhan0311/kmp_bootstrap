package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ErrorLanguageTest {
    @AfterTest fun reset() = Language.reset()

    @Test fun apiErrorsSpeakTheCurrentLanguageButServerMessagesAreKept() = runTest {
        fun client(status: HttpStatusCode, body: String) = AuthedClient(HttpTransport("https://x.test", "a", MockEngine { respond(body, status) }, getRetries = 0), MemoryTokenStore("t", "r"), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        Language.current = "vi"
        assertTrue(assertFailsWith<ApiError> { client(HttpStatusCode.NotFound, "{}").get<Map<String, String>>("/x") }.message.contains("Không tìm thấy"))
        Language.current = "en"
        assertEquals("Not found.", assertFailsWith<ApiError> { client(HttpStatusCode.NotFound, "{}").get<Map<String, String>>("/x") }.message)
        assertEquals("Email taken", assertFailsWith<ApiError> { client(HttpStatusCode.UnprocessableEntity, """{"message":"Email taken"}""").get<Map<String, String>>("/x") }.message, "lời của server giữ nguyên")
        val down = AuthedClient(HttpTransport("https://x.test", "a", MockEngine { throw RuntimeException("Unable to resolve host") }, getRetries = 0), MemoryTokenStore(), PlainJsonContract(TEST_PLAIN), refreshMarginSeconds = 0)
        assertEquals("Can't reach the server. Check your connection.", assertFailsWith<ApiError> { down.get<Map<String, String>>("/x") }.message)
        Language.current = "zh"
        assertTrue(assertFailsWith<ApiError> { down.get<Map<String, String>>("/x") }.message.contains("无法连接"))
    }
}

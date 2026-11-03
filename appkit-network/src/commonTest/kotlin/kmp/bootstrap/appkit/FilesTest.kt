package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesTest {
    private fun tokensPair(a: String, r: String) = """{"access_token":"$a","refresh_token":"$r"}"""

    @Test
    fun downloadReturnsBytesAndHeaders() = runTest {
        val engine = MockEngine {
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, io.ktor.http.Headers.build {
                append(HttpHeaders.ContentType, "application/vnd.ant.fit; charset=x")
                append(HttpHeaders.ContentDisposition, "attachment; filename=\"a-b.fit\"")
                append("X-Request-Id", "req-1")
            })
        }
        val client = AuthedClient("https://x.test", MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        val file = client.download("/api/f")
        assertEquals(3, file.bytes.size)
        assertEquals("application/vnd.ant.fit", file.contentType)
        assertEquals("a-b.fit", file.filename)
        assertEquals("req-1", file.requestId)
    }

    @Test
    fun downloadRefreshesOnceAfterAnExpiredToken() = runTest {
        var refreshes = 0
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/refresh") -> { refreshes++; respond(tokensPair("new", "r2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                req.headers[HttpHeaders.Authorization] == "Bearer new" -> respond(byteArrayOf(9), HttpStatusCode.OK)
                else -> respond("""{"message":"expired"}""", HttpStatusCode.Unauthorized)
            }
        }
        val tokens = MemoryTokenStore("old", "r1")
        val client = AuthedClient("https://x.test", tokens, PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        val file = client.download("/api/f")
        assertEquals(200, file.status); assertEquals(1, file.bytes.size); assertEquals(1, refreshes)
        assertEquals("new", tokens.access())
    }

    @Test
    fun downloadErrorKeepsTheServerSentence() = runTest {
        val engine = MockEngine { respond("""{"message":"khong co GPS"}""", HttpStatusCode.UnprocessableEntity) }
        val client = AuthedClient("https://x.test", MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        val file = client.download("/api/f")
        assertEquals(422, file.status)
        assertEquals("khong co GPS", client.errorMessageOf(RawResponse(file.status, file.text(), file.requestId)))
    }

    @Test
    fun downloadWithoutAuthSendsNoBearer() = runTest {
        var bearer: String? = "unset"
        val engine = MockEngine { req -> bearer = req.headers[HttpHeaders.Authorization]; respond(byteArrayOf(1), HttpStatusCode.OK) }
        val client = AuthedClient("https://x.test", MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        client.download("/api/public/f", auth = false)
        assertNull(bearer)
    }

    @Test
    fun uploadSendsMultipartAndRefreshesOnce() = runTest {
        var refreshes = 0
        var sawMultipart = false
        val engine = MockEngine { req ->
            when {
                req.url.encodedPath.endsWith("/refresh") -> { refreshes++; respond(tokensPair("new", "r2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                req.headers[HttpHeaders.Authorization] == "Bearer new" -> {
                    sawMultipart = req.body is OutgoingContent && req.body.contentType?.match(io.ktor.http.ContentType.MultiPart.FormData) == true
                    respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respond("""{"message":"expired"}""", HttpStatusCode.Unauthorized)
            }
        }
        val client = AuthedClient("https://x.test", MemoryTokenStore("old", "r1"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        val r = client.upload("POST", "/api/me/avatar", listOf(FormPart("avatar", byteArrayOf(1, 2), "a.png", "image/png")))
        assertEquals(200, r.status); assertEquals(1, refreshes); assertTrue(sawMultipart, "phải gửi multipart/form-data")
    }
}

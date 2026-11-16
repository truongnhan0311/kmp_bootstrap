package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogUploaderTest {
    private val url = "https://bucket.example.com/logs/req-1.ndjson.gz?X-Amz-Signature=abcdef0123456789secret"
    private suspend fun bundle() = LogExport.bundle("2026-11-16T10:00:00Z I/app: started\n2026-11-16T10:00:01Z E/app: crashed", LogExport.Options())

    @Test fun putsTheGzipWithoutAuthorization() = runTest {
        var body: ByteArray? = null; var method: HttpMethod? = null; var auth: String? = "unset"; var type: String? = null
        val engine = MockEngine { req ->
            method = req.method; body = req.body.toByteArray(); auth = req.headers[HttpHeaders.Authorization]; type = req.body.contentType?.toString()
            respond("", HttpStatusCode.OK)
        }
        val b = bundle()
        val r = assertIs<LogUploadResult.Done>(LogUploader(engine).upload(b, url))
        assertEquals(HttpMethod.Put, method); assertContentEquals(b.gzip, body); assertNull(auth); assertEquals("application/gzip", type)
        assertEquals(LogUploadResult.Done(b.gzip.size, b.sha256, 2, false), r)
    }

    @Test fun retriesServerErrorsButNotRefusals() = runTest {
        var calls = 0
        val flaky = MockEngine { calls++; respond("", if (calls == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK) }
        assertIs<LogUploadResult.Done>(LogUploader(flaky, backoffMs = 1).upload(bundle(), url)); assertEquals(2, calls)
        calls = 0
        val expired = MockEngine { calls++; respond("", HttpStatusCode.Forbidden) }
        assertEquals(LogUploadResult.Failed("upload_failed", "HTTP 403"), LogUploader(expired, backoffMs = 1).upload(bundle(), url))
        assertEquals(1, calls, "URL hết hạn: thử lại vô ích")
        calls = 0
        val down = MockEngine { calls++; throw RuntimeException("connection reset") }
        assertEquals("upload_failed", (LogUploader(down, retries = 2, backoffMs = 1).upload(bundle(), url) as LogUploadResult.Failed).code)
        assertEquals(3, calls)
    }

    @Test fun nothingToSendAndInsecureUrlsAreRefused() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("", HttpStatusCode.OK) }
        val empty = LogExport.bundle("", LogExport.Options())
        assertEquals("no_logs", (LogUploader(engine).upload(empty, url) as LogUploadResult.Failed).code); assertEquals(0, calls)
        assertFailsWith<IllegalArgumentException> { LogUploader(engine).upload(bundle(), "http://bucket.example.com/x") }
        assertIs<LogUploadResult.Done>(LogUploader(engine).upload(bundle(), "http://127.0.0.1:9000/x"))
    }

    @Test fun signedUrlNeverReachesTheLog() = runTest {
        val lines = mutableListOf<String>()
        AppLog.install(object : LogSink { override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) { lines += message } })
        try {
            LogUploader(MockEngine { respond("", HttpStatusCode.OK) }).upload(bundle(), url)
            LogUploader(MockEngine { respond("", HttpStatusCode.Forbidden) }).upload(bundle(), url)
        } finally { AppLog.install() }
        assertTrue(lines.size >= 2 && lines.all { "bucket.example.com" in it })
        assertFalse(lines.any { "secret" in it || "Signature" in it || "/logs/req-1" in it })
    }
}

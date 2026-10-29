package kmp.bootstrap.appkit

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTest {
    private val dir = "/logs".toPath()

    @Test fun passwordsAndTokensNeverReachTheFile() = runTest {
        val fs = FakeFileSystem()
        val sink = RotatingFileSink(dir, fs = fs)
        sink.write(LogLevel.Info, "auth", """login {"email":"a@b.c","password":"hunter2","refresh_token":"abcDEF123"} Authorization: Bearer eyJhbGciOi.payload.sig""", null)
        sink.flush()
        val text = sink.readAll()
        assertFalse(text.contains("hunter2")); assertFalse(text.contains("abcDEF123")); assertFalse(text.contains("eyJhbGciOi"))
        assertTrue(text.contains("a@b.c")) // chỉ che bí mật, không che mọi thứ (log để gỡ lỗi)
        assertTrue(text.contains("[redacted]"))
    }

    @Test fun rotatesWhenTooBigAndKeepsOnlyTheConfiguredNumberOfFiles() = runTest {
        val fs = FakeFileSystem()
        val sink = RotatingFileSink(dir, maxBytes = 200, keepFiles = 3, maxAgeMillis = null, fs = fs)
        repeat(60) { sink.write(LogLevel.Info, "t", "dòng số $it ${"x".repeat(30)}", null) }
        sink.flush()
        val files = fs.list(dir).map { it.name }.sorted()
        assertEquals(listOf("app.log", "app.log.1", "app.log.2", "app.log.3"), files, "chỉ giữ 3 file cũ + file hiện tại")
        assertTrue(fs.metadata(dir / "app.log").size!! < 400)
        assertTrue(sink.readAll().contains("dòng số 59"), "dòng mới nhất còn")
        assertFalse(sink.readAll().contains("dòng số 0 "), "dòng cũ nhất đã bị xoá khi xoay vòng")
    }

    @Test fun oldRotatedFilesAreDeletedByAge() = runTest {
        val day = 24L * 3600 * 1000
        var now = 1_000_000_000_000L
        val clock = object : kotlinx.datetime.Clock { override fun now() = kotlinx.datetime.Instant.fromEpochMilliseconds(now) }
        val fs = FakeFileSystem(clock)
        val sink = RotatingFileSink(dir, maxBytes = 100, keepFiles = 3, maxAgeMillis = 15 * day, fs = fs, now = { now })
        repeat(10) { sink.write(LogLevel.Info, "t", "x".repeat(50), null) }
        sink.flush()
        assertTrue(fs.exists(dir / "app.log.1"))
        now += 16 * day          // file xoay vòng cũ hơn 15 ngày
        sink.write(LogLevel.Info, "t", "mới", null); sink.flush()
        assertTrue(fs.list(dir).none { it.name.startsWith("app.log.") && fs.metadata(it).lastModifiedAtMillis!! < now - 15 * day }, "không còn file cũ quá 15 ngày")
    }

    @Test fun aBrokenSinkNeverBreaksTheApp() {
        AppLog.install(object : LogSink { override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) = error("hỏng") })
        AppLog.i("x", "vẫn chạy") // không ném
        AppLog.install()
    }

    @Test fun minLevelFilters() {
        val seen = mutableListOf<String>()
        AppLog.install(object : LogSink { override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) { seen += message } })
        AppLog.minLevel = LogLevel.Warn
        AppLog.d("t", "d"); AppLog.w("t", "w")
        AppLog.minLevel = LogLevel.Debug; AppLog.install()
        assertEquals(listOf("w"), seen)
    }
}

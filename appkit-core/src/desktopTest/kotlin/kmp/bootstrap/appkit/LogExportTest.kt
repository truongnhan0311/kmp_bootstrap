package kmp.bootstrap.appkit

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Gzip được kiểm bằng GZIPInputStream của Java (bộ giải nén độc lập, kiểm cả CRC-32 và độ dài ở cuối tệp). */
class GzipTest {
    private fun gunzip(b: ByteArray) = GZIPInputStream(ByteArrayInputStream(b)).readAllBytes()

    @Test fun roundTripsEverythingThroughJavasGunzip() {
        val rnd = Random(7)
        val cases = listOf(
            ByteArray(0), byteArrayOf(65), "aaa".encodeToByteArray(), ByteArray(1000) { 'x'.code.toByte() },
            ByteArray(100_000) { rnd.nextInt(256).toByte() },                                // không nén được
            ByteArray(300_000) { "abcab"[it % 5].code.toByte() },                              // lặp, khoảng cách ngắn
            ByteArray(200_000) { i -> if (i % 40_000 < 20_000) (i % 251).toByte() else ((i - 20_000) % 251).toByte() },   // khớp ở xa
            (1..3000).joinToString("\n") { "2026-11-16T10:00:${it % 60}Z I/mqtt: connected as dev$it to 203.0.113.${it % 255}" }.encodeToByteArray(),
            "Tiếng Việt có dấu 👶 ".repeat(500).encodeToByteArray(),
        )
        for (c in cases) assertContentEquals(c, gunzip(Gzip.compress(c)), "độ dài ${c.size}")
        repeat(200) { val c = ByteArray(rnd.nextInt(0, 3000)) { rnd.nextInt(4).toByte() }; assertContentEquals(c, gunzip(Gzip.compress(c))) }
    }

    @Test fun everyMatchLengthAndDistanceCode() {
        // Mỗi độ dài 3..258 (29 mã độ dài + bit thêm) và các khoảng cách ở biên của 30 mã khoảng cách.
        val rnd = Random(11)
        for (len in 3..258) {
            val chunk = ByteArray(len) { rnd.nextInt(256).toByte() }
            val c = chunk + byteArrayOf(1, 2, 3, 4, 5) + chunk + byteArrayOf(9)
            assertContentEquals(c, gunzip(Gzip.compress(c)), "độ dài $len")
        }
        val dists = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 16, 17, 24, 25, 32, 33, 48, 49, 64, 65, 96, 97, 128, 129, 192, 193, 256, 257, 384, 385, 512, 513, 768,
            769, 1024, 1025, 1536, 1537, 2048, 2049, 3072, 3073, 4096, 4097, 6144, 6145, 8192, 8193, 12288, 12289, 16384, 16385, 24576, 24577, 32768)
        for (d in dists) {
            val head = ByteArray(d) { rnd.nextInt(256).toByte() }
            val c = ByteArray(d + 8) { head[it % d] }                    // 8 byte cuối lặp lại đúng ở khoảng cách d
            assertContentEquals(c, gunzip(Gzip.compress(c)), "khoảng cách $d")
        }
    }

    @Test fun compressesLogTextAndCrcMatchesJava() {
        val log = (1..5000).joinToString("\n") { "2026-11-16T10:${it % 60}:00Z I/webrtc: viewer ice restart $it, state=Connected" }.encodeToByteArray()
        val gz = Gzip.compress(log)
        assertTrue(gz.size * 4 < log.size, "log phải nhỏ đi ít nhất 4 lần (được ${log.size} → ${gz.size})")
        val crc = CRC32().apply { update(log) }.value.toInt()
        assertEquals(crc, Gzip.crc32(log))
        assertEquals(0x1f, gz[0].toInt() and 0xff); assertEquals(0x8b, gz[1].toInt() and 0xff)
    }
}

class LogExportTest {
    private val log = listOf(
        "2026-11-16T10:00:03Z D/mqtt: ping",
        "2026-11-16T10:00:01Z I/auth: login ok for an@example.com from 203.0.113.77",
        "garbage line without format",
        "2026-11-16T10:00:02Z W/webrtc: relay 2001:db8:85a3:8d3:1319:8a2e:370:7348 slow",
        "2026-11-16T09:00:00Z E/old: before the window",
        "2026-11-16T10:00:04Z E/mqtt: disconnected: broker silent",
    ).joinToString("\n")
    private val since = kotlinx.datetime.Instant.parse("2026-11-16T09:30:00Z").toEpochMilliseconds()

    @Test fun filtersSortsAndScrubs() {
        val lines = LogExport.ndjson(log, LogExport.Options(minLevel = LogLevel.Info, sinceMillis = since))
        val objs = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf("auth", "webrtc", "mqtt"), objs.map { it["comp"]!!.jsonPrimitive.content }, "theo thời gian; bỏ debug, dòng cũ, dòng hỏng")
        assertEquals(listOf("info", "warn", "error"), objs.map { it["lvl"]!!.jsonPrimitive.content })
        assertEquals(kotlinx.datetime.Instant.parse("2026-11-16T10:00:01Z").toEpochMilliseconds(), objs[0]["ts"]!!.jsonPrimitive.long)
        val text = lines.joinToString("\n")
        listOf("an@example.com", "203.0.113.77", "8a2e:370:7348").forEach { assertFalse(it in text, "lộ $it") }
        assertTrue("[email]" in text && "203.0.113.x" in text && "2001:db8:85a3::x" in text)
    }

    @Test fun scrubLeavesNonAddressesAlone() {
        assertEquals("version 1.2.3.400 build", LogExport.scrub("version 1.2.3.400 build"), "số > 255 không phải IP")
        assertEquals("10.0.0.x:3478", LogExport.scrub("10.0.0.2:3478"))
    }

    @Test fun bundleIsGzipOfNdjsonAndKeepsNewestWhenTooLarge() = runTest {
        val all = LogExport.bundle(log, LogExport.Options(minLevel = LogLevel.Debug))
        val text = GZIPInputStream(ByteArrayInputStream(all.gzip)).readAllBytes().decodeToString()
        assertEquals(5, all.lines); assertEquals(5, text.lines().filter { it.isNotEmpty() }.size); assertFalse(all.truncated)
        assertEquals(Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(all.gzip)), all.sha256, "sha256 của đúng tệp tải lên")
        val oneLine = text.lines().last { it.isNotEmpty() }.encodeToByteArray().size + 1
        val small = LogExport.bundle(log, LogExport.Options(minLevel = LogLevel.Debug, maxBytes = oneLine * 2))
        assertTrue(small.truncated); assertEquals(2, small.lines)
        val kept = GZIPInputStream(ByteArrayInputStream(small.gzip)).readAllBytes().decodeToString()
        assertTrue("disconnected" in kept && "ping" in kept && "login" !in kept, "giữ 2 dòng MỚI nhất")
        val none = LogExport.bundle("", LogExport.Options())
        assertEquals(0, none.lines)
    }
}

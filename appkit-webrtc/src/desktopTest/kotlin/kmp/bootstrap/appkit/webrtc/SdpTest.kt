package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offer dạng Chrome tạo ra (rút gọn): video VP8, VP9, hai biến thể H.264 kèm RTX, rồi audio Opus. */
private val CHROME_OFFER = listOf(
    "v=0", "o=- 4611731400430051336 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1",
    "m=video 9 UDP/TLS/RTP/SAVPF 96 97 98 99 100 101 102 103",
    "c=IN IP4 0.0.0.0",
    "a=candidate:842163049 1 udp 1677729535 198.51.100.7 50123 typ srflx raddr 192.168.1.20 rport 50123 generation 0",
    "a=candidate:1 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active generation 0",
    "a=ice-ufrag:abcd", "a=ice-pwd:secretsecretsecretsecret00",
    "a=fingerprint:sha-256 7B:8B:F0:65:5F:78:E2:51:3B:AC:6F:F3:3F:46:1B:35",
    "a=setup:actpass", "a=mid:0", "a=recvonly",
    "a=rtpmap:96 VP8/90000", "a=rtpmap:97 rtx/90000", "a=fmtp:97 apt=96",
    "a=rtpmap:98 VP9/90000", "a=rtpmap:99 rtx/90000", "a=fmtp:99 apt=98",
    "a=rtpmap:100 H264/90000", "a=fmtp:100 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f",
    "a=rtpmap:101 rtx/90000", "a=fmtp:101 apt=100",
    "a=rtpmap:102 H264/90000", "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
    "a=rtpmap:103 rtx/90000", "a=fmtp:103 apt=102",
    "m=audio 9 UDP/TLS/RTP/SAVPF 111", "c=IN IP4 0.0.0.0", "a=mid:1", "a=rtpmap:111 opus/48000/2",
).joinToString("\r\n") + "\r\n"

class SdpTest {
    private fun mLine(sdp: String, kind: String) = sdp.lines().first { it.startsWith("m=$kind ") }.trim()

    @Test fun fingerprintAndSetup() {
        assertTrue(Sdp.hasDtlsFingerprint(CHROME_OFFER)); assertEquals("actpass", Sdp.setupRole(CHROME_OFFER))
        assertFalse(Sdp.hasDtlsFingerprint("v=0\r\na=fingerprint:\r\n"), "fingerprint rỗng không tính")
        assertFalse(Sdp.hasDtlsFingerprint("v=0\na=setup:actpass\n"))
        assertNull(Sdp.setupRole("v=0\r\n"))
    }

    @Test fun preferConstrainedBaselineH264WithItsRtx() {
        val out = Sdp.preferCodec(CHROME_OFFER, "H264") { "profile-level-id=42e01f" in it }
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 103 96 97 98 99 100 101", mLine(out, "video"))
        assertEquals("m=audio 9 UDP/TLS/RTP/SAVPF 111", mLine(out, "audio"), "phần audio giữ nguyên")
        assertEquals(CHROME_OFFER.lines().size, out.lines().size, "không thêm/bớt dòng nào")
        assertTrue(out.contains("\r\n") && !out.replace("\r\n", "").contains("\n"), "giữ xuống dòng \\r\\n")
        val anyH264 = Sdp.preferCodec(CHROME_OFFER, "h264")
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 100 101 102 103 96 97 98 99", mLine(anyH264, "video"), "giữ thứ tự gốc giữa các H.264")
        assertEquals(CHROME_OFFER, Sdp.preferCodec(CHROME_OFFER, "AV1"), "không có codec thì trả nguyên văn")
        assertEquals(CHROME_OFFER, Sdp.preferCodec(CHROME_OFFER, "opus", kind = "data"), "không có phần media đó")
    }

    @Test fun bitrateLimitReplacesOldLinesAfterConnectionLine() {
        val once = Sdp.limitBitrate(CHROME_OFFER, 800)
        val twice = Sdp.limitBitrate(once, 500)
        val video = twice.lines().dropWhile { !it.startsWith("m=video") }.takeWhile { !it.startsWith("m=audio") }.map { it.trim() }
        assertEquals(listOf("c=IN IP4 0.0.0.0", "b=AS:500", "b=TIAS:500000"), video.subList(1, 4))
        assertEquals(1, video.count { it.startsWith("b=AS:") }, "lần sau thay lần trước")
        assertFalse(twice.lines().dropWhile { !it.startsWith("m=audio") }.any { it.startsWith("b=") }, "audio không bị giới hạn")
        assertFailsWith<IllegalArgumentException> { Sdp.limitBitrate(CHROME_OFFER, 0) }
    }

    @Test fun redactKeepsShapeButNoAddressesOrSecrets() {
        val r = Sdp.redact(CHROME_OFFER)
        listOf("198.51.100.7", "192.168.1.20", "50123", "abcd", "secretsecret", "7B:8B").forEach { assertFalse(it in r, "lộ $it") }
        assertTrue("a=candidate:udp typ srflx" in r && "a=candidate:tcp typ host" in r)
        assertTrue("m=video 9 UDP/TLS/RTP/SAVPF" in r && "a=rtpmap:102 H264/90000" in r && "a=recvonly" in r && "a=setup:actpass" in r)
    }

    @Test fun candidateParsing() {
        val c = IceCandidateInfo.parse("candidate:1 1 tcp 1518280447 192.168.1.20 9 typ host tcptype active generation 0")!!
        assertEquals(IceCandidateInfo("tcp", "192.168.1.20", 9, "host", "active"), c)
        assertTrue(IceCandidateInfo.parse("a=candidate:2 1 UDP 1 203.0.113.9 3478 typ relay raddr 0.0.0.0 rport 0")!!.isRelay)
        assertNull(IceCandidateInfo.parse("")); assertNull(IceCandidateInfo.parse("candidate:1 1 udp 1 1.2.3.4 x typ host"))
    }
}

class IceConfigTest {
    private val now = 1_763_280_000_000L

    @Test fun turnRestExpiryIsReadFromTheUsername() {
        val turn = IceServer(listOf("turn:relay.example.com:3478?transport=udp", "turns:relay.example.com:443?transport=tcp"), "1763366400:session-1", "c2VjcmV0")
        assertEquals(1_763_366_400_000, turn.expiresAt)
        val cfg = IceConfig.of(listOf(IceServer(listOf("stun:stun.example.com:3478"))), listOf(turn), listOf(turn))
        assertEquals(2, cfg.servers.size, "bỏ trùng"); assertTrue(cfg.hasRelay)
        assertTrue(cfg.isFresh(now)); assertFalse(cfg.isFresh(1_763_366_400_000 - 3_599_000), "còn dưới 1 giờ thì coi như hết")
        assertTrue(IceConfig(listOf(IceServer(listOf("stun:s:3478")))).isFresh(Long.MAX_VALUE / 2), "STUN không hết hạn")
    }

    @Test fun badServersAreRejected() {
        assertFailsWith<IllegalArgumentException> { IceServer(emptyList()) }
        assertFailsWith<IllegalArgumentException> { IceServer(listOf("http://x")) }
        assertFailsWith<IllegalArgumentException> { IceServer(listOf("turn:1763366400:s:pw@relay:3478"), "u", "p") }
        assertFailsWith<IllegalArgumentException> { IceServer(listOf("turn:relay:3478")) }
    }

    @Test fun sourceFetchesOnceAndAgainNearExpiry() = runTest {
        var clock = now; var fetches = 0
        val source = IceConfigSource(fetch = {
            fetches++
            IceConfig(listOf(IceServer(listOf("turn:r:3478"), "${clock / 1000 + 86_400}:s", "c")))   // sống 24 giờ
        }, nowMillis = { clock })
        (1..5).map { async { source.get() } }.awaitAll()
        assertEquals(1, fetches, "nhiều nơi gọi cùng lúc chỉ xin một lần")
        clock += 22 * 3_600_000L; source.get(); assertEquals(1, fetches)
        clock += 1 * 3_600_000L + 1; source.get(); assertEquals(2, fetches, "còn dưới 1 giờ thì xin lại")
        source.invalidate(); source.get(); assertEquals(3, fetches)
    }
}

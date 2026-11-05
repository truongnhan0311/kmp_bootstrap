package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdpToolsTest {
    // Rút gọn từ một offer thật của libwebrtc (VP8 đứng trước, nhiều profile H.264, rtx, red, ulpfec).
    private val offer = listOf(
        "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0", "a=group:BUNDLE 0 1 2",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111", "c=IN IP4 0.0.0.0", "a=mid:0", "a=recvonly",
        "a=fingerprint:sha-256 AA:BB", "a=setup:actpass", "a=rtpmap:111 opus/48000/2",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 127 125 116 117", "c=IN IP4 0.0.0.0", "a=mid:1", "a=recvonly",
        "a=fingerprint:sha-256 AA:BB", "a=setup:actpass",
        "a=rtpmap:96 VP8/90000", "a=rtcp-fb:96 nack", "a=rtpmap:97 rtx/90000", "a=fmtp:97 apt=96",
        "a=rtpmap:102 H264/90000", "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f",
        "a=rtpmap:103 rtx/90000", "a=fmtp:103 apt=102",
        "a=rtpmap:127 H264/90000", "a=fmtp:127 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "a=rtcp-fb:127 nack", "a=rtpmap:125 rtx/90000", "a=fmtp:125 apt=127",
        "a=rtpmap:116 red/90000", "a=rtpmap:117 ulpfec/90000",
        "m=application 9 UDP/DTLS/SCTP webrtc-datachannel", "c=IN IP4 0.0.0.0", "a=mid:2",
        "a=fingerprint:sha-256 AA:BB", "a=setup:actpass", "a=sctp-port:5000",
    ).joinToString("\r\n", postfix = "\r\n")

    private fun mVideo(sdp: String) = sdp.lines().first { it.startsWith("m=video") }.trim()

    @Test fun readsCodecsAndDirection() {
        assertEquals(listOf("VP8", "H264"), SdpTools.codecs(offer))
        assertEquals(listOf("OPUS"), SdpTools.codecs(offer, "audio"))
        assertEquals("recvonly", SdpTools.direction(offer, "video"))
        assertEquals(null, SdpTools.direction(offer, "nope"))
    }

    @Test fun preferH264ThenVp8KeepsRtxNextToItsCodec() {
        val out = SdpTools.preferVideoCodecs(offer, listOf("H264", "VP8"))
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 103 127 125 96 97 116 117", mVideo(out))
        assertEquals(listOf("H264", "VP8"), SdpTools.codecs(out))
        assertTrue(out.endsWith("\r\n") && !out.contains("\r\r"), "line endings preserved")
        assertEquals(offer.lines().size, out.lines().size, "reorder never drops lines")
    }

    @Test fun dropOthersRemovesTheirAttributes() {
        val out = SdpTools.preferVideoCodecs(offer, listOf("H264"), dropOthers = true)
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 102 103 127 125", mVideo(out))
        assertFalse(out.contains("VP8")); assertFalse(out.contains("a=rtcp-fb:96")); assertFalse(out.contains("apt=96"))
        assertFalse(out.contains("ulpfec"))
        assertTrue(out.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111"), "other sections untouched")
    }

    @Test fun keepOnlyConstrainedBaselineH264() {
        val out = SdpTools.keepH264Profile(offer, "42e01f", packetizationMode = 1)
        assertEquals("m=video 9 UDP/TLS/RTP/SAVPF 96 97 127 125 116 117", mVideo(out))
        assertFalse(out.contains("profile-level-id=42001f")); assertFalse(out.contains("apt=102"))
    }

    @Test fun fingerprintCheck() {
        assertTrue(SdpTools.hasDtlsFingerprint(offer))
        val missingInOneSection = offer.replaceFirst("a=fingerprint:sha-256 AA:BB\r\na=setup:actpass\r\na=rtpmap:96", "a=setup:actpass\r\na=rtpmap:96")
        assertFalse(SdpTools.hasDtlsFingerprint(missingInOneSection))
        assertFalse(SdpTools.hasDtlsFingerprint(offer.replace("sha-256", "sha-1")))
        val sessionLevel = "v=0\no=- 1 1 IN IP4 0.0.0.0\ns=-\nt=0 0\na=fingerprint:sha-256 AA\nm=video 9 UDP/TLS/RTP/SAVPF 96\na=rtpmap:96 VP8/90000\n"
        assertTrue(SdpTools.hasDtlsFingerprint(sessionLevel))
        assertFalse(SdpTools.hasDtlsFingerprint("v=0\ns=-\n"))
    }

    @Test fun negotiatedCodecFollowsEncoderSupport() = runTest {
        // Viewer: libwebrtc liệt kê VP8 trước; ép H.264 lên đầu.
        val viewer = FakeRtcEngine().createPeer(emptyList())
        viewer.addTransceiver(MediaKind.Video, Direction.RecvOnly)
        viewer.addTransceiver(MediaKind.Audio, Direction.SendRecv)
        viewer.createDataChannel("ctrl")
        val raw = viewer.createOffer()
        assertEquals(listOf("VP8", "H264"), SdpTools.codecs(raw.sdp))
        val offer = raw.copy(sdp = SdpTools.preferVideoCodecs(raw.sdp, listOf("H264", "VP8")))

        suspend fun answerFrom(engine: FakeRtcEngine): String {
            val cam = engine.createPeer(emptyList())
            cam.addTransceiver(MediaKind.Video, Direction.SendOnly, engine.createCameraTrack())
            cam.setRemoteDescription(offer)
            return cam.createAnswer().sdp
        }
        assertEquals("H264", SdpTools.negotiatedVideoCodec(answerFrom(FakeRtcEngine(encoders = listOf("VP8", "H264")))))
        // Máy Android không có H.264 phần cứng → libwebrtc không mã hoá H.264 được → rơi về VP8.
        assertEquals("VP8", SdpTools.negotiatedVideoCodec(answerFrom(FakeRtcEngine(encoders = listOf("VP8")))))
        assertEquals("sendonly", SdpTools.direction(answerFrom(FakeRtcEngine()), "video"))
        assertTrue(SdpTools.hasDtlsFingerprint(answerFrom(FakeRtcEngine())))
    }
}

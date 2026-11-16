package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * VÍ DỤ CHẠY ĐƯỢC của appkit-webrtc (peer và kênh báo hiệu giả). Các đoạn giữa `// region` và `// endregion` được chép vào docs/webrtc.md.
 */
class WebRtcExamples {
    @Test fun iceAndSdp() = runTest {
        val offerSdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96 102\r\nc=IN IP4 0.0.0.0\r\na=fingerprint:sha-256 AA\r\n" +
            "a=rtpmap:96 VP8/90000\r\na=rtpmap:102 H264/90000\r\na=fmtp:102 packetization-mode=1;profile-level-id=42e01f\r\n"
        // region: webrtc-ice
        // Thông tin TURN do server cấp (TURN REST): hạn nằm trong username, đưa cho thư viện thành TRƯỜNG RIÊNG.
        val source = IceConfigSource(fetch = {
            IceConfig.of(
                listOf(IceServer(listOf("stun:stun.example.com:3478"))),
                listOf(IceServer(listOf("turn:relay.example.com:3478?transport=udp", "turns:relay.example.com:443?transport=tcp"),
                    username = "4102444800:session-1", credential = "base64-hmac")),
            )
        })
        val ice = source.get()               // chỉ gọi server khi chưa có hoặc còn dưới 1 giờ
        // endregion
        // region: webrtc-sdp
        require(Sdp.hasDtlsFingerprint(offerSdp))                                   // thiếu DTLS → từ chối
        var sdp = Sdp.preferCodec(offerSdp, "H264") { "profile-level-id=42e01f" in it }   // chip mã hoá của điện thoại
        sdp = Sdp.limitBitrate(sdp, kbps = 1200)                                    // theo gói hoặc sức máy camera
        println(Sdp.redact(sdp))                                                    // ghi log: không IP, không ufrag/pwd
        // endregion
        assertTrue(ice.hasRelay); assertTrue("m=video 9 UDP/TLS/RTP/SAVPF 102 96" in sdp); assertTrue("b=AS:1200" in sdp)
    }

    @Test fun call() = runTest {
        val net = FakeNetwork()
        val (toCamera, toViewer) = FakeSignaling.pair()
        val viewerPeer: RtcPeer = FakePeer(net)          // trong app: peer thật (K3b) dựng với ice.servers
        val cameraPeer: RtcPeer = FakePeer(net)
        val source = IceConfigSource(fetch = { IceConfig(emptyList()) })
        CallSession(CallRole.Callee, cameraPeer, toViewer, backgroundScope).start()
        val scope = backgroundScope
        val signaling: Signaling = toCamera              // trong app: chuyển SignalMessage ↔ thông điệp MQTT/REST của mình
        // region: webrtc-call
        val call = CallSession(
            CallRole.Caller, viewerPeer, signaling, scope,
            timeouts = CallTimeouts(answer = 10.seconds, ice = 20.seconds),     // mặc định đã đúng như vậy
            beforeIceRestart = { source.invalidate(); source.get() },           // đổi mạng giữa chừng: xin TURN mới rồi mới restart
        )
        call.start()
        when (val s = call.state.first { it == CallState.Connected || it is CallState.Ended }) {
            CallState.Connected -> println("nối sau ${call.setupTime}")
            is CallState.Ended -> println("thất bại: ${s.reason.code}")         // no_answer, timeout, no_path, insecure_sdp…
            else -> Unit
        }
        call.hangup()                                                           // báo bên kia, đóng peer
        // endregion
        assertEquals(CallEnd.LocalHangup, (call.state.value as CallState.Ended).reason)
    }

}

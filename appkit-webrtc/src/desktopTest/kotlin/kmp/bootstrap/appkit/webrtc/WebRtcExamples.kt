package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VÍ DỤ CHẠY ĐƯỢC của appkit-webrtc (chép vào docs/webrtc.md bằng tools/sync-snippets.mjs). */
class WebRtcExamples {
    // region: webrtc-offer
    // Bên XEM tạo offer: nhận video, audio hai chiều (để bật nói chuyện sau bằng replaceTrack, không đàm phán lại),
    // DataChannel "ctrl" cho lệnh. libwebrtc liệt kê VP8 trước H.264 nên ép lại thứ tự.
    suspend fun viewerOffer(rtc: RtcEngine, ice: List<IceServer>): Pair<PeerConnection, SessionDescription> {
        val peer = rtc.createPeer(ice)
        peer.addTransceiver(MediaKind.Video, Direction.RecvOnly)
        peer.addTransceiver(MediaKind.Audio, Direction.SendRecv)    // chưa gắn micro: không gửi gì
        peer.createDataChannel("ctrl")
        val raw = peer.createOffer()
        val offer = raw.copy(sdp = SdpTools.preferVideoCodecs(raw.sdp, listOf("H264", "VP8")))
        peer.setLocalDescription(offer)
        return peer to offer                                        // gửi offer.sdp qua kênh signaling của app
    }
    // endregion

    // region: webrtc-answer
    // Bên CAMERA trả lời: từ chối SDP không có DTLS, nhận candidate đến sớm qua CandidateQueue.
    suspend fun cameraAnswer(rtc: RtcEngine, ice: List<IceServer>, offer: SessionDescription, camera: LocalTrack): SessionDescription? {
        if (!SdpTools.hasDtlsFingerprint(offer.sdp)) return null     // không có a=fingerprint → không trả lời
        val peer = rtc.createPeer(ice)
        val candidates = CandidateQueue(peer)                         // signaling gọi candidates.add(c) cho mỗi candidate đến
        peer.addTransceiver(MediaKind.Video, Direction.SendOnly, camera)
        peer.setRemoteDescription(offer)
        candidates.remoteDescriptionSet()
        val answer = peer.createAnswer()
        peer.setLocalDescription(answer)
        return answer                                                 // SdpTools.negotiatedVideoCodec(answer.sdp): "H264" hoặc "VP8"
    }
    // endregion

    @Test fun viewerAndCameraNegotiate() = runTest {
        // region: webrtc-codecs
        val rtc: RtcEngine = FakeRtcEngine(encoders = listOf("VP8"))  // Android thật: AndroidRtcEngine()
        val canSendH264 = "H264" in rtc.videoEncoders()                 // false: máy không có H.264 phần cứng → sẽ gửi VP8
        // endregion
        val (_, offer) = viewerOffer(FakeRtcEngine(), emptyList())
        assertEquals(listOf("H264", "VP8"), SdpTools.codecs(offer.sdp))
        val answer = cameraAnswer(rtc, emptyList(), offer, rtc.createCameraTrack())!!
        assertEquals(false, canSendH264)
        assertEquals("VP8", SdpTools.negotiatedVideoCodec(answer.sdp))
        assertTrue(SdpTools.hasDtlsFingerprint(answer.sdp))
        assertEquals(null, cameraAnswer(rtc, emptyList(), offer.copy(sdp = offer.sdp.replace("a=fingerprint", "a=x-fp")), rtc.createCameraTrack()))
    }
}

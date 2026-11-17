package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIView
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * RtcPeer thật cho iOS. libwebrtc (gói Swift `WebRTC`, github.com/stasel/WebRTC) dùng từ Swift gọn hơn cinterop, nên Swift cài
 * hai giao diện dưới đây (mẫu `SwiftRtcPeer.swift` ở docs/webrtc.md) và gán một lần lúc khởi động:
 *
 *     IosRtc.shared.factory = WebRtcFactory()      // lớp Swift trong docs/webrtc.md; framework phải export appkit-webrtc
 *
 * Kotlin dùng [IosRtcPeer] như mọi RtcPeer khác (CallSession lo thứ tự). Giao diện dùng callback, chuỗi và số để Swift cài được.
 */

/** Một RTCPeerConnection phía Swift. Mọi callback gọi đúng một lần. */
interface SwiftRtcPeer {
    /** `sdp` hoặc `error`. */
    fun createOffer(iceRestart: Boolean, done: (sdp: String?, error: String?) -> Unit)
    fun createAnswer(done: (sdp: String?, error: String?) -> Unit)
    /** [type] là "offer" hoặc "answer"; `error` null = thành công. */
    fun setLocal(type: String, sdp: String, done: (error: String?) -> Unit)
    fun setRemote(type: String, sdp: String, done: (error: String?) -> Unit)
    fun addCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int, done: (error: String?) -> Unit)
    /** Khung vẽ hình: hình nhận được (người xem) hoặc xem trước camera (camera). */
    fun videoView(): UIView?
    fun switchCamera()
    fun close()
}

/** Swift báo sự kiện về Kotlin. */
interface SwiftRtcListener {
    fun onCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int)
    /** Hết candidate. */
    fun onGatheringComplete()
    /** "new", "connecting", "connected", "disconnected", "failed", "closed". */
    fun onConnectionState(state: String)
}

interface SwiftRtcFactory {
    /**
     * [iceJson]: mảng `[{"urls":[…],"username":…,"credential":…}]` (username/credential là trường riêng).
     * [sendCamera]: true = gửi camera sau + micro (điện thoại làm camera); false = chỉ nhận hình/tiếng, có DataChannel "ctrl".
     */
    fun create(iceJson: String, sendCamera: Boolean, listener: SwiftRtcListener): SwiftRtcPeer
}

object IosRtc {
    @kotlin.concurrent.Volatile
    var factory: SwiftRtcFactory? = null
}

class IosRtcPeer(ice: List<IceServer>, sendCamera: Boolean) : RtcPeer {
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 128)
    override val events: Flow<PeerEvent> = _events

    val swift: SwiftRtcPeer = (IosRtc.factory ?: error("IosRtc.shared.factory is not set (see docs/webrtc.md)"))
        .create(iceJson(ice), sendCamera, object : SwiftRtcListener {
            override fun onCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
                _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate(candidate, sdpMid, sdpMLineIndex)))
            }
            override fun onGatheringComplete() { _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate(""))) }
            override fun onConnectionState(state: String) {
                _events.tryEmit(PeerEvent.StateChanged(when (state) {
                    "connecting" -> PeerState.Connecting
                    "connected" -> PeerState.Connected
                    "disconnected" -> PeerState.Disconnected
                    "failed" -> PeerState.Failed
                    "closed" -> PeerState.Closed
                    else -> PeerState.New
                }))
            }
        })

    override suspend fun createOffer(iceRestart: Boolean) = sdp(SdpType.Offer) { swift.createOffer(iceRestart, it) }
    override suspend fun createAnswer() = sdp(SdpType.Answer) { swift.createAnswer(it) }
    override suspend fun setLocalDescription(description: SessionDescription) = unit { swift.setLocal(description.type.wire, description.sdp, it) }
    override suspend fun setRemoteDescription(description: SessionDescription) = unit { swift.setRemote(description.type.wire, description.sdp, it) }
    override suspend fun addIceCandidate(candidate: IceCandidate) {
        if (candidate.isEnd) return
        unit { swift.addCandidate(candidate.candidate, candidate.sdpMid, candidate.sdpMLineIndex ?: 0, it) }
    }
    override fun close() = swift.close()

    private suspend fun sdp(type: SdpType, call: ((String?, String?) -> Unit) -> Unit): SessionDescription = suspendCancellableCoroutine { c ->
        call { sdp, error -> if (c.isActive) { if (sdp != null) c.resume(SessionDescription(type, sdp)) else c.resumeWithException(IllegalStateException(error ?: "sdp")) } }
    }

    private suspend fun unit(call: ((String?) -> Unit) -> Unit): Unit = suspendCancellableCoroutine { c ->
        call { error -> if (c.isActive) { if (error == null) c.resume(Unit) else c.resumeWithException(IllegalStateException(error)) } }
    }

    private val SdpType.wire get() = if (this == SdpType.Offer) "offer" else "answer"

    private fun iceJson(ice: List<IceServer>): String = ice.joinToString(",", "[", "]") { s ->
        buildString {
            append("{\"urls\":").append(s.urls.joinToString(",", "[", "]") { q(it) })
            s.username?.let { append(",\"username\":").append(q(it)) }
            s.credential?.let { append(",\"credential\":").append(q(it)) }
            append('}')
        }
    }

    private fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

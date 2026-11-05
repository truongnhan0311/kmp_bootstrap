package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * API WebRTC trung lập nền tảng. Phần chung (signaling, logic phiên) viết trên các interface này;
 * mỗi nền tảng cài [RtcEngine]:
 *  - Android: AndroidRtcEngine (libwebrtc `io.github.webrtc-sdk:android`), trong androidMain của module này.
 *  - iOS:     CHƯA có trong kit — app tự cài RtcEngine (Swift qua pod `WebRTC-SDK`, hoặc cinterop). Xem docs/webrtc.md.
 *  - Test:    FakeRtcEngine (commonMain), chạy trên JVM.
 * Kit không lo signaling: SDP và ICE candidate đi đường nào (REST, MQTT…) là việc của app.
 */

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

enum class SdpType { Offer, Answer }
data class SessionDescription(val type: SdpType, val sdp: String)

/** Một ICE candidate. `candidate` rỗng = bên kia đã gom xong (end-of-candidates). */
data class IceCandidate(val candidate: String, val sdpMid: String?, val sdpMLineIndex: Int?)

enum class MediaKind { Audio, Video }
enum class Direction { SendRecv, SendOnly, RecvOnly, Inactive }
enum class PeerState { New, Connecting, Connected, Disconnected, Failed, Closed }

sealed interface PeerEvent {
    data class LocalCandidate(val candidate: IceCandidate) : PeerEvent
    data object GatheringComplete : PeerEvent
    data class State(val state: PeerState) : PeerEvent
    /** Bên kia mở DataChannel (bên trả lời nhận kênh qua sự kiện này). */
    data class RemoteDataChannel(val channel: DataChannel) : PeerEvent
    /** Track của bên kia. [native] là đối tượng của nền tảng (Android: org.webrtc.VideoTrack/AudioTrack) để app tự hiển thị. */
    data class RemoteTrack(val kind: MediaKind, val native: Any) : PeerEvent
}

sealed interface ChannelMessage {
    data class Text(val text: String) : ChannelMessage
    class Binary(val bytes: ByteArray) : ChannelMessage
}

interface DataChannel {
    val label: String
    val isOpen: Boolean
    val messages: Flow<ChannelMessage>
    /** false khi kênh chưa mở hoặc đã đóng. */
    fun send(text: String): Boolean
    fun close()
}

/** Track cục bộ (camera, micro). Một track dùng được cho nhiều PeerConnection. */
interface LocalTrack {
    val kind: MediaKind
    var enabled: Boolean
    fun dispose()
}

interface Transceiver {
    val kind: MediaKind
    val direction: Direction
    /** Gắn/gỡ track gửi đi mà KHÔNG đàm phán lại SDP (WebRTC `replaceTrack`). null = ngừng gửi. */
    fun replaceTrack(track: LocalTrack?)
}

interface PeerConnection {
    val events: Flow<PeerEvent>
    fun addTransceiver(kind: MediaKind, direction: Direction, track: LocalTrack? = null): Transceiver
    fun createDataChannel(label: String, ordered: Boolean = true): DataChannel
    suspend fun createOffer(): SessionDescription
    suspend fun createAnswer(): SessionDescription
    suspend fun setLocalDescription(description: SessionDescription)
    suspend fun setRemoteDescription(description: SessionDescription)
    suspend fun addIceCandidate(candidate: IceCandidate)
    fun close()
}

data class CameraSpec(val front: Boolean = false, val width: Int = 1280, val height: Int = 720, val fps: Int = 30)

interface RtcEngine {
    fun createPeer(iceServers: List<IceServer>): PeerConnection
    fun createCameraTrack(spec: CameraSpec = CameraSpec()): LocalTrack
    fun createMicrophoneTrack(): LocalTrack
    /** Codec video mà máy MÃ HOÁ được (chữ hoa, ví dụ "H264", "VP8"). Android không có H.264 phần mềm: thiếu chip thì không có "H264". */
    fun videoEncoders(): List<String>
}

/**
 * Giữ ICE candidate của bên kia đến TRƯỚC khi đặt remote description (trickle ICE đến lộn thứ tự là chuyện thường),
 * rồi đưa vào PeerConnection đúng thứ tự khi đã sẵn sàng. Thiếu bước này thì addIceCandidate báo lỗi và mất candidate.
 */
class CandidateQueue(private val peer: PeerConnection) {
    private val lock = Mutex()
    private val waiting = mutableListOf<IceCandidate>()
    private var ready = false

    suspend fun add(candidate: IceCandidate) {
        val now = lock.withLock { if (ready) true else { waiting += candidate; false } }
        if (now) peer.addIceCandidate(candidate)
    }

    /** Gọi ngay sau setRemoteDescription. */
    suspend fun remoteDescriptionSet() {
        val flush = lock.withLock { ready = true; waiting.toList().also { waiting.clear() } }
        flush.forEach { peer.addIceCandidate(it) }
    }

    suspend fun pending(): Int = lock.withLock { waiting.size }
}

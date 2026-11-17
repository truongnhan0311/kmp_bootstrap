package kmp.bootstrap.appkit.webrtc

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.webrtc.IceCandidate as RtcIce
import org.webrtc.SessionDescription as RtcSdp

/*
 * RtcPeer thật cho Android, trên libwebrtc của Google (io.getstream:stream-webrtc-android, API org.webrtc).
 *
 *     val rtc = AndroidRtc.get(context)                                    // một lần cho cả app (EGL + factory)
 *     val cam = AndroidRtcPeer(rtc, ice, PeerMedia.SendCamera())          // điện thoại làm camera: gửi hình + tiếng
 *     val view = AndroidRtcPeer(rtc, ice, PeerMedia.Receive())            // người xem: chỉ nhận
 *     view.remoteVideo.collect { track -> track?.addSink(renderer) }      // renderer = rtc.newRenderer(context)
 *
 * Cần quyền CAMERA (+ RECORD_AUDIO nếu có tiếng) trước khi dựng SendCamera. Unified Plan; bên gọi tạo DataChannel "ctrl"
 * để SDP có phần application (PROTOCOL của Recam). Mã hoá phần cứng khi máy có (DefaultVideoEncoderFactory).
 */

/** Media của một peer. */
sealed interface PeerMedia {
    /** Gửi camera (sau mặc định, vì điện thoại cũ dựng để nhìn ra phòng) và micro; không nhận gì. */
    data class SendCamera(val front: Boolean = false, val width: Int = 1280, val height: Int = 720, val fps: Int = 30, val audio: Boolean = true) : PeerMedia
    /** Chỉ nhận hình (và tiếng). Bên gọi (người xem) tạo cả DataChannel "ctrl". */
    data class Receive(val audio: Boolean = true) : PeerMedia
}

/** EGL + PeerConnectionFactory dùng chung (tạo một lần, giữ suốt đời app). */
class AndroidRtc private constructor(val context: Context) {
    val egl: EglBase = EglBase.create()
    val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    /** Khung vẽ video (View). Gắn bằng `track.addSink(renderer)`; gọi `renderer.release()` khi bỏ. */
    fun newRenderer(context: Context): SurfaceViewRenderer = SurfaceViewRenderer(context).apply {
        init(egl.eglBaseContext, null)
        setEnableHardwareScaler(true)
    }

    companion object {
        @Volatile private var instance: AndroidRtc? = null
        fun get(context: Context): AndroidRtc = instance ?: synchronized(this) { instance ?: AndroidRtc(context.applicationContext).also { instance = it } }
    }
}

class AndroidRtcPeer(private val rtc: AndroidRtc, ice: List<IceServer>, private val media: PeerMedia) : RtcPeer {
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 128)
    override val events: Flow<PeerEvent> = _events

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    /** Hình nhận được (người xem). */
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()
    /** Hình camera của chính máy này (xem trước ở màn "Camera đang chạy"). */
    var localVideo: VideoTrack? = null; private set

    private var capturer: CameraVideoCapturer? = null
    private var textures: SurfaceTextureHelper? = null
    private var audio: AudioTrack? = null
    private var ctrl: DataChannel? = null
    @Volatile private var closed = false

    private val pc: PeerConnection

    init {
        val servers = ice.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }   // trường riêng: username TURN REST có dấu ':'
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        pc = rtc.factory.createPeerConnection(config, Observer()) ?: error("createPeerConnection failed")
        when (media) {
            is PeerMedia.SendCamera -> startCamera(media)
            is PeerMedia.Receive -> {
                pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                if (media.audio) pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                ctrl = pc.createDataChannel("ctrl", DataChannel.Init().apply { ordered = true })
            }
        }
    }

    private fun startCamera(m: PeerMedia.SendCamera) {
        val enumerator = Camera2Enumerator(rtc.context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == m.front } ?: enumerator.deviceNames.firstOrNull()
            ?: error("no camera on this device")
        val cap = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("recam-capture", rtc.egl.eglBaseContext)
        val source = rtc.factory.createVideoSource(false)
        cap.initialize(helper, rtc.context, source.capturerObserver)
        cap.startCapture(m.width, m.height, m.fps)
        capturer = cap; textures = helper
        val video = rtc.factory.createVideoTrack("video0", source)
        localVideo = video
        // addTrack, KHÔNG addTransceiver: camera là bên trả answer, và khi áp offer của bên kia, chỉ transceiver sinh từ addTrack mới
        // được ghép vào m-line của offer (theo chuẩn JSEP). Offer recvonly ∩ sendrecv → answer sendonly.
        pc.addTrack(video, listOf("cam"))
        if (m.audio) {
            val track = rtc.factory.createAudioTrack("audio0", rtc.factory.createAudioSource(MediaConstraints()))
            audio = track
            pc.addTrack(track, listOf("cam"))
        }
    }

    /** Đổi camera trước/sau (chỉ SendCamera). */
    fun switchCamera() { capturer?.switchCamera(null) }

    /** Hạ/nâng chất lượng gửi (bậc T1–T3 của PROTOCOL) mà không cần đàm phán lại. */
    fun setVideoQuality(width: Int, height: Int, fps: Int) { capturer?.changeCaptureFormat(width, height, fps) }

    /** Tắt/bật tiếng phía camera. */
    fun setAudioEnabled(on: Boolean) { audio?.setEnabled(on) }

    override suspend fun createOffer(iceRestart: Boolean): SessionDescription {
        val c = MediaConstraints().apply { if (iceRestart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true") }
        return create { pc.createOffer(it, c) }
    }

    override suspend fun createAnswer(): SessionDescription = create { pc.createAnswer(it, MediaConstraints()) }

    override suspend fun setLocalDescription(description: SessionDescription) = set { pc.setLocalDescription(it, description.toRtc()) }

    override suspend fun setRemoteDescription(description: SessionDescription) = set { pc.setRemoteDescription(it, description.toRtc()) }

    override suspend fun addIceCandidate(candidate: IceCandidate) {
        if (candidate.isEnd) return
        val ok = pc.addIceCandidate(RtcIce(candidate.sdpMid ?: "0", candidate.sdpMLineIndex ?: 0, candidate.candidate))
        check(ok) { "candidate rejected" }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); textures?.dispose()
        ctrl?.dispose()
        pc.dispose()   // giải phóng cả các track/transceiver của nó
        _remoteVideo.value = null
        _events.tryEmit(PeerEvent.StateChanged(PeerState.Closed))
    }

    private suspend fun create(call: (SdpObserver) -> Unit): SessionDescription = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(sdp: RtcSdp) { if (cont.isActive) cont.resume(sdp.toKit()) }
            override fun onCreateFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException("create sdp: $error")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
    }

    private suspend fun set(call: (SdpObserver) -> Unit): Unit = suspendCancellableCoroutine { cont ->
        call(object : SdpObserver {
            override fun onCreateSuccess(sdp: RtcSdp?) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) { if (cont.isActive) cont.resumeWithException(IllegalStateException("set sdp: $error")) }
        })
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(c: RtcIce) { _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex))) }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate("")))
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (closed) return
            _events.tryEmit(PeerEvent.StateChanged(when (state) {
                PeerConnection.PeerConnectionState.NEW -> PeerState.New
                PeerConnection.PeerConnectionState.CONNECTING -> PeerState.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> PeerState.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> PeerState.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> PeerState.Failed
                PeerConnection.PeerConnectionState.CLOSED -> PeerState.Closed
            }))
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.let { _remoteVideo.value = it }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out RtcIce>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
    }
}

private fun SessionDescription.toRtc() = RtcSdp(if (type == SdpType.Offer) RtcSdp.Type.OFFER else RtcSdp.Type.ANSWER, sdp)
private fun RtcSdp.toKit() = SessionDescription(if (type == RtcSdp.Type.OFFER) SdpType.Offer else SdpType.Answer, description)

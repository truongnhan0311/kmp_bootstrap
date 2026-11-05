package kmp.bootstrap.appkit.webrtc

import android.content.Context
import kmp.bootstrap.appkit.AppKitAndroid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SurfaceTextureHelper
import java.nio.ByteBuffer
import java.util.UUID
import org.webrtc.DataChannel as NativeDataChannel
import org.webrtc.IceCandidate as NativeIceCandidate
import org.webrtc.PeerConnection as NativePeerConnection
import org.webrtc.SessionDescription as NativeSessionDescription

/*
 * RtcEngine cho Android, dựa trên libwebrtc `io.github.webrtc-sdk:android` (cùng bản flutter_webrtc dùng).
 * CHƯA biên dịch và CHƯA chạy trên thiết bị (môi trường dựng kit không có Android SDK) — xem docs/webrtc.md.
 *
 *     AppKitAndroid.init(applicationContext)
 *     val rtc = AndroidRtcEngine()            // một bản cho cả app; giữ EglBase dùng chung để hiển thị video
 *
 * Bộ mã hoá: DefaultVideoEncoderFactory (phần cứng trước, phần mềm dự phòng). Phần mềm của libwebrtc Android KHÔNG có H.264,
 * nên máy thiếu chip H.264 chỉ còn VP8 — dùng [videoEncoders] để biết trước khi khai `capabilities`.
 */
class AndroidRtcEngine(context: Context = AppKitAndroid.context()) : RtcEngine {
    private val appContext = context.applicationContext
    val eglBase: EglBase = EglBase.create()
    private val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
    private val factory: PeerConnectionFactory

    init {
        initializeOnce(appContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    override fun videoEncoders(): List<String> = encoderFactory.supportedCodecs.map { it.name.uppercase() }.distinct()

    override fun createPeer(iceServers: List<IceServer>): PeerConnection {
        val servers = iceServers.map { s ->
            NativePeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = NativePeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = NativePeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = NativePeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return AndroidPeer(factory, config)
    }

    override fun createCameraTrack(spec: CameraSpec): LocalTrack {
        val enumerator = Camera2Enumerator(appContext)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == spec.front }
            ?: enumerator.deviceNames.firstOrNull()
            ?: error("no camera on this device")
        val capturer = enumerator.createCapturer(name, null)
        val helper = SurfaceTextureHelper.create("appkit-camera", eglBase.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, appContext, source.capturerObserver)
        capturer.startCapture(spec.width, spec.height, spec.fps)
        val track = factory.createVideoTrack("v-" + UUID.randomUUID(), source)
        return AndroidTrack(MediaKind.Video, track) {
            runCatching { capturer.stopCapture() }
            capturer.dispose(); helper.dispose(); source.dispose()
        }
    }

    override fun createMicrophoneTrack(): LocalTrack {
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("a-" + UUID.randomUUID(), source)
        return AndroidTrack(MediaKind.Audio, track) { source.dispose() }
    }

    private companion object {
        @Volatile var initialized = false
        @Synchronized fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            initialized = true
        }
    }
}

class AndroidTrack internal constructor(
    override val kind: MediaKind,
    /** org.webrtc.VideoTrack / AudioTrack — để app gắn vào SurfaceViewRenderer khi cần xem trước. */
    val native: MediaStreamTrack,
    private val release: () -> Unit,
) : LocalTrack {
    override var enabled: Boolean
        get() = native.enabled()
        set(value) { native.setEnabled(value) }
    override fun dispose() { native.dispose(); release() }
}

private class AndroidTransceiver(private val t: RtpTransceiver, override val kind: MediaKind) : Transceiver {
    override val direction: Direction get() = t.direction.toCommon()
    override fun replaceTrack(track: LocalTrack?) { t.sender.setTrack((track as AndroidTrack?)?.native, false) }
}

private class AndroidDataChannel(private val ch: NativeDataChannel) : DataChannel {
    private val incoming = MutableSharedFlow<ChannelMessage>(extraBufferCapacity = 256)
    override val label: String = ch.label()
    override val isOpen: Boolean get() = ch.state() == NativeDataChannel.State.OPEN
    override val messages: Flow<ChannelMessage> = incoming.asSharedFlow()

    init {
        ch.registerObserver(object : NativeDataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: NativeDataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                incoming.tryEmit(if (buffer.binary) ChannelMessage.Binary(bytes) else ChannelMessage.Text(bytes.decodeToString()))
            }
        })
    }

    override fun send(text: String): Boolean =
        isOpen && ch.send(NativeDataChannel.Buffer(ByteBuffer.wrap(text.encodeToByteArray()), false))

    override fun close() { ch.close() }
}

private class AndroidPeer(factory: PeerConnectionFactory, config: NativePeerConnection.RTCConfiguration) : PeerConnection {
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 256)
    override val events: Flow<PeerEvent> = _events.asSharedFlow()

    private val pc: NativePeerConnection = checkNotNull(factory.createPeerConnection(config, object : NativePeerConnection.Observer {
        override fun onSignalingChange(state: NativePeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: NativePeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: NativePeerConnection.IceGatheringState) {
            if (state == NativePeerConnection.IceGatheringState.COMPLETE) _events.tryEmit(PeerEvent.GatheringComplete)
        }
        override fun onIceCandidate(c: NativeIceCandidate) {
            _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate(c.sdp, c.sdpMid, c.sdpMLineIndex)))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out NativeIceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: NativeDataChannel) { _events.tryEmit(PeerEvent.RemoteDataChannel(AndroidDataChannel(channel))) }
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            val track = receiver.track() ?: return
            val kind = if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) MediaKind.Video else MediaKind.Audio
            _events.tryEmit(PeerEvent.RemoteTrack(kind, track))
        }
        override fun onConnectionChange(state: NativePeerConnection.PeerConnectionState) {
            _events.tryEmit(PeerEvent.State(when (state) {
                NativePeerConnection.PeerConnectionState.NEW -> PeerState.New
                NativePeerConnection.PeerConnectionState.CONNECTING -> PeerState.Connecting
                NativePeerConnection.PeerConnectionState.CONNECTED -> PeerState.Connected
                NativePeerConnection.PeerConnectionState.DISCONNECTED -> PeerState.Disconnected
                NativePeerConnection.PeerConnectionState.FAILED -> PeerState.Failed
                NativePeerConnection.PeerConnectionState.CLOSED -> PeerState.Closed
            }))
        }
    })) { "PeerConnectionFactory.createPeerConnection returned null" }

    override fun addTransceiver(kind: MediaKind, direction: Direction, track: LocalTrack?): Transceiver {
        val init = RtpTransceiver.RtpTransceiverInit(direction.toNative())
        val t = if (track != null) pc.addTransceiver((track as AndroidTrack).native, init)
        else pc.addTransceiver(if (kind == MediaKind.Video) MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO else MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
        return AndroidTransceiver(t, kind)
    }

    override fun createDataChannel(label: String, ordered: Boolean): DataChannel =
        AndroidDataChannel(pc.createDataChannel(label, NativeDataChannel.Init().apply { this.ordered = ordered }))

    override suspend fun createOffer() = create { pc.createOffer(it, MediaConstraints()) }
    override suspend fun createAnswer() = create { pc.createAnswer(it, MediaConstraints()) }
    override suspend fun setLocalDescription(description: SessionDescription) = set { pc.setLocalDescription(it, description.toNative()) }
    override suspend fun setRemoteDescription(description: SessionDescription) = set { pc.setRemoteDescription(it, description.toNative()) }

    override suspend fun addIceCandidate(candidate: IceCandidate) {
        if (candidate.candidate.isEmpty()) return       // end-of-candidates: libwebrtc Android không cần báo
        pc.addIceCandidate(NativeIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex ?: 0, candidate.candidate))
    }

    override fun close() { pc.close() }

    private suspend fun create(call: (SdpObserver) -> Unit): SessionDescription {
        val done = CompletableDeferred<SessionDescription>()
        call(object : SdpObserver {
            override fun onCreateSuccess(d: NativeSessionDescription) {
                done.complete(SessionDescription(if (d.type == NativeSessionDescription.Type.OFFER) SdpType.Offer else SdpType.Answer, d.description))
            }
            override fun onCreateFailure(error: String?) { done.completeExceptionally(IllegalStateException("create SDP failed: $error")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String?) = Unit
        })
        return done.await()
    }

    private suspend fun set(call: (SdpObserver) -> Unit) {
        val done = CompletableDeferred<Unit>()
        call(object : SdpObserver {
            override fun onCreateSuccess(d: NativeSessionDescription) = Unit
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetSuccess() { done.complete(Unit) }
            override fun onSetFailure(error: String?) { done.completeExceptionally(IllegalStateException("set SDP failed: $error")) }
        })
        done.await()
    }
}

private fun SessionDescription.toNative() = NativeSessionDescription(
    if (type == SdpType.Offer) NativeSessionDescription.Type.OFFER else NativeSessionDescription.Type.ANSWER, sdp)

private fun Direction.toNative() = when (this) {
    Direction.SendRecv -> RtpTransceiver.RtpTransceiverDirection.SEND_RECV
    Direction.SendOnly -> RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
    Direction.RecvOnly -> RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
    Direction.Inactive -> RtpTransceiver.RtpTransceiverDirection.INACTIVE
}

private fun RtpTransceiver.RtpTransceiverDirection.toCommon() = when (this) {
    RtpTransceiver.RtpTransceiverDirection.SEND_RECV -> Direction.SendRecv
    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY -> Direction.SendOnly
    RtpTransceiver.RtpTransceiverDirection.RECV_ONLY -> Direction.RecvOnly
    else -> Direction.Inactive
}


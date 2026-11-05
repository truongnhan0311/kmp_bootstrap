package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * Bản giả của RtcEngine để test logic phiên trên JVM (giống FakePlatformServices). Sinh SDP có hình dạng thật:
 * fingerprint, setup, BUNDLE, rtx kèm `apt=`, DataChannel SCTP. Offer liệt kê codec theo [decoders] (libwebrtc mặc định
 * VP8 trước H.264, nên muốn H.264 trước thì phải SdpTools.preferVideoCodecs). Answer chỉ giữ codec của offer mà máy
 * mã hoá được ([encoders]), theo thứ tự của offer — giống cách WebRTC thật chọn.
 */
class FakeRtcEngine(
    val encoders: List<String> = listOf("VP8", "H264"),
    val decoders: List<String> = listOf("VP8", "H264"),
) : RtcEngine {
    val peers = mutableListOf<FakePeerConnection>()
    override fun createPeer(iceServers: List<IceServer>) = FakePeerConnection(this, iceServers).also { peers += it }
    override fun createCameraTrack(spec: CameraSpec): LocalTrack = FakeTrack(MediaKind.Video)
    override fun createMicrophoneTrack(): LocalTrack = FakeTrack(MediaKind.Audio)
    override fun videoEncoders() = encoders
}

class FakeTrack(override val kind: MediaKind) : LocalTrack {
    override var enabled = true
    var disposed = false; private set
    override fun dispose() { disposed = true }
}

class FakeTransceiver(override val kind: MediaKind, override val direction: Direction, track: LocalTrack?) : Transceiver {
    var track: LocalTrack? = track; private set
    var replaceCount = 0; private set
    override fun replaceTrack(track: LocalTrack?) { this.track = track; replaceCount++ }
}

class FakeDataChannel(override val label: String) : DataChannel {
    override var isOpen = true; private set
    val sent = mutableListOf<String>()
    private val incoming = MutableSharedFlow<ChannelMessage>(extraBufferCapacity = 64)
    override val messages: Flow<ChannelMessage> = incoming.asSharedFlow()
    override fun send(text: String): Boolean { if (!isOpen) return false; sent += text; return true }
    override fun close() { isOpen = false }
    /** Test: giả lập bên kia gửi tới. */
    fun receive(text: String) { incoming.tryEmit(ChannelMessage.Text(text)) }
}

class FakePeerConnection(private val engine: FakeRtcEngine, val iceServers: List<IceServer>) : PeerConnection {
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64)
    override val events: Flow<PeerEvent> = _events.asSharedFlow()
    val transceivers = mutableListOf<FakeTransceiver>()
    val channels = mutableListOf<FakeDataChannel>()
    val addedCandidates = mutableListOf<IceCandidate>()
    var local: SessionDescription? = null; private set
    var remote: SessionDescription? = null; private set
    var closed = false; private set

    override fun addTransceiver(kind: MediaKind, direction: Direction, track: LocalTrack?) =
        FakeTransceiver(kind, direction, track).also { transceivers += it }

    override fun createDataChannel(label: String, ordered: Boolean) = FakeDataChannel(label).also { channels += it }

    override suspend fun createOffer() = SessionDescription(SdpType.Offer, sdp(offer = true, videoCodecs = engine.decoders))

    override suspend fun createAnswer(): SessionDescription {
        val offer = checkNotNull(remote) { "createAnswer before setRemoteDescription" }
        check(offer.type == SdpType.Offer) { "remote description is not an offer" }
        val codecs = SdpTools.codecs(offer.sdp, "video").filter { it in engine.encoders }
        return SessionDescription(SdpType.Answer, sdp(offer = false, videoCodecs = codecs, remoteOffer = offer.sdp))
    }

    override suspend fun setLocalDescription(description: SessionDescription) { local = description }
    override suspend fun setRemoteDescription(description: SessionDescription) { remote = description }

    override suspend fun addIceCandidate(candidate: IceCandidate) {
        check(remote != null) { "addIceCandidate before setRemoteDescription" }
        addedCandidates += candidate
    }

    override fun close() { closed = true; _events.tryEmit(PeerEvent.State(PeerState.Closed)) }

    /** Test: phát một sự kiện như nền tảng thật. */
    fun emit(event: PeerEvent) { _events.tryEmit(event) }

    private fun sdp(offer: Boolean, videoCodecs: List<String>, remoteOffer: String? = null): String {
        val pt = mapOf("VP8" to 96, "H264" to 102, "VP9" to 98)
        val b = StringBuilder()
        fun line(s: String) { b.append(s).append("\r\n") }
        line("v=0"); line("o=- 4611731400430051336 2 IN IP4 127.0.0.1"); line("s=-"); line("t=0 0")
        line("a=fingerprint:sha-256 7B:8B:F0:65:5F:78:E2:51:3B:AC:6F:F3:3F:46:1B:35:DC:B8:5F:64:1A:24:C2:43:F0:A1:58:D0:A1:2C:19:08")
        var mid = 0
        fun common(dir: Direction) {
            line("c=IN IP4 0.0.0.0"); line("a=mid:${mid++}")
            line("a=setup:" + if (offer) "actpass" else "active")
            line("a=" + dir.name.lowercase())
        }
        for (t in transceivers.ifEmpty { remoteKinds(remoteOffer) }) {
            val dir = if (offer) t.direction else answerDirection(remoteOffer, t.kind, t.track != null)
            if (t.kind == MediaKind.Video) {
                val pts = videoCodecs.mapNotNull { c -> pt[c]?.let { listOf(it, it + 1) } }.flatten()
                line("m=video 9 UDP/TLS/RTP/SAVPF " + pts.joinToString(" "))
                common(dir)
                for (c in videoCodecs) {
                    val p = pt[c] ?: continue
                    line("a=rtpmap:$p $c/90000")
                    if (c == "H264") line("a=fmtp:$p level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f")
                    line("a=rtcp-fb:$p nack")
                    line("a=rtpmap:${p + 1} rtx/90000"); line("a=fmtp:${p + 1} apt=$p")
                }
            } else {
                line("m=audio 9 UDP/TLS/RTP/SAVPF 111"); common(dir)
                line("a=rtpmap:111 opus/48000/2"); line("a=fmtp:111 minptime=10;useinbandfec=1")
            }
        }
        if (channels.isNotEmpty() || remoteOffer?.contains("m=application") == true) {
            line("m=application 9 UDP/DTLS/SCTP webrtc-datachannel"); line("c=IN IP4 0.0.0.0"); line("a=mid:${mid++}")
            line("a=setup:" + if (offer) "actpass" else "active"); line("a=sctp-port:5000")
        }
        return b.toString()
    }

    private fun remoteKinds(remoteOffer: String?): List<FakeTransceiver> {
        if (remoteOffer == null) return emptyList()
        return remoteOffer.lines().filter { it.startsWith("m=audio") || it.startsWith("m=video") }
            .map { FakeTransceiver(if (it.startsWith("m=video")) MediaKind.Video else MediaKind.Audio, Direction.Inactive, null) }
    }

    private fun answerDirection(remoteOffer: String?, kind: MediaKind, sending: Boolean): Direction {
        val theirs = remoteOffer?.let { SdpTools.direction(it, kind.name.lowercase()) } ?: "sendrecv"
        val theyReceive = theirs == "sendrecv" || theirs == "recvonly"
        val theySend = theirs == "sendrecv" || theirs == "sendonly"
        return when {
            theyReceive && theySend -> if (sending) Direction.SendRecv else Direction.RecvOnly
            theyReceive -> if (sending) Direction.SendOnly else Direction.Inactive
            theySend -> Direction.RecvOnly
            else -> Direction.Inactive
        }
    }
}

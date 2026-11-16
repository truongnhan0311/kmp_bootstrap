package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** "Mạng" giữa hai peer giả: [pathOpen] = ICE có tìm được đường không. */
class FakeNetwork { var pathOpen = true }

/**
 * Peer giả theo đúng các luật của RTCPeerConnection thật mà CallSession phải tôn trọng:
 *  - addIceCandidate trước setRemoteDescription → ném lỗi;
 *  - candidate của thế hệ ICE cũ (trước ICE restart) → ném lỗi;
 *  - chỉ `Connected` khi có cả hai mô tả, có candidate của bên kia, và mạng có đường.
 */
class FakePeer(private val net: FakeNetwork, var withFingerprint: Boolean = true) : RtcPeer {
    private val _events = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 64)
    override val events: Flow<PeerEvent> = _events
    var state = PeerState.New; private set
    var local: SessionDescription? = null; private set
    var remote: SessionDescription? = null; private set
    private var generation = 1
    private var remoteGeneration = 0
    private var remoteCandidates = 0
    val log = mutableListOf<String>()
    var closed = false; private set

    private fun sdp(type: SdpType) = buildString {
        append("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
        append("m=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=ice-ufrag:g$generation\r\n")
        if (withFingerprint) append("a=fingerprint:sha-256 AA:BB\r\n")
        append("a=setup:${if (type == SdpType.Offer) "actpass" else "active"}\r\na=rtpmap:96 H264/90000\r\n")
    }

    override suspend fun createOffer(iceRestart: Boolean): SessionDescription {
        if (iceRestart) generation++
        log += "createOffer(restart=$iceRestart)"
        return SessionDescription(SdpType.Offer, sdp(SdpType.Offer))
    }

    override suspend fun createAnswer(): SessionDescription {
        check(remote?.type == SdpType.Offer) { "no remote offer" }
        log += "createAnswer"
        return SessionDescription(SdpType.Answer, sdp(SdpType.Answer))
    }

    override suspend fun setLocalDescription(description: SessionDescription) {
        local = description; log += "setLocal(${description.type})"
        if (state == PeerState.New) emitState(PeerState.Connecting)
        listOf(Triple("host", "10.0.0.2", 50000), Triple("relay", "203.0.113.9", 3478)).forEachIndexed { i, (type, ip, port) ->
            _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate("candidate:$i 1 udp 100 $ip $port typ $type generation g$generation", "0", 0)))
        }
        _events.tryEmit(PeerEvent.LocalCandidate(IceCandidate("")))   // hết candidate
        tryConnect()
    }

    override suspend fun setRemoteDescription(description: SessionDescription) {
        remote = description; log += "setRemote(${description.type})"
        val g = Regex("a=ice-ufrag:g(\\d+)").find(description.sdp)!!.groupValues[1].toInt()
        if (g != remoteGeneration) {   // bên kia ICE restart: thế hệ mới, đếm lại candidate, mình cũng đổi thông tin ICE
            if (remoteGeneration != 0 && description.type == SdpType.Offer) generation++
            remoteGeneration = g; remoteCandidates = 0
        }
    }

    override suspend fun addIceCandidate(candidate: IceCandidate) {
        check(remote != null) { "remote description not set" }
        if (candidate.isEnd) return
        val g = Regex("generation g(\\d+)").find(candidate.candidate)!!.groupValues[1].toInt()
        check(g == remoteGeneration) { "candidate from ice generation $g, current is $remoteGeneration" }
        remoteCandidates++; log += "addIce"
        tryConnect()
    }

    override fun close() { closed = true; state = PeerState.Closed; _events.tryEmit(PeerEvent.StateChanged(PeerState.Closed)) }

    private fun tryConnect() {
        if (net.pathOpen && local != null && remote != null && remoteCandidates > 0 && state != PeerState.Connected) emitState(PeerState.Connected)
    }

    fun emitState(s: PeerState) { state = s; _events.tryEmit(PeerEvent.StateChanged(s)) }
}

/** Một đầu của kênh báo hiệu giả. Tin gửi đi qua [route] (đổi thứ tự, bỏ, giữ lại…) rồi tới đầu kia. */
class FakeSignaling : Signaling {
    private val inbox = Channel<SignalMessage>(Channel.UNLIMITED)
    override val incoming: Flow<SignalMessage> = inbox.receiveAsFlow()
    val sent = mutableListOf<SignalMessage>()
    var peerEnd: FakeSignaling? = null
    /** Biến một tin gửi đi thành những tin thực sự tới đầu kia (mặc định: giữ nguyên). */
    var route: (SignalMessage) -> List<SignalMessage> = { listOf(it) }

    /** Chạy trước khi tin thực sự đi (vd `delay` để giả mạng chậm). */
    var beforeSend: suspend (SignalMessage) -> Unit = {}

    override suspend fun send(message: SignalMessage) {
        beforeSend(message)
        sent += message
        route(message).forEach { peerEnd?.inbox?.send(it) }
    }

    /** Thả một tin vào như thể đầu kia gửi. */
    suspend fun inject(message: SignalMessage) = inbox.send(message)

    companion object {
        fun pair(): Pair<FakeSignaling, FakeSignaling> {
            val a = FakeSignaling(); val b = FakeSignaling(); a.peerEnd = b; b.peerEnd = a
            return a to b
        }
    }
}

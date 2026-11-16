package kmp.bootstrap.appkit.webrtc

import kmp.bootstrap.appkit.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// ---------------------------------------------------------------- phần thư viện WebRTC thật phải cài (K3b: Android, iOS)

enum class SdpType { Offer, Answer }

data class SessionDescription(val type: SdpType, val sdp: String)

/** Một candidate ICE. [candidate] rỗng = bên kia đã thu thập xong ("end-of-candidates"). */
data class IceCandidate(val candidate: String, val sdpMid: String? = null, val sdpMLineIndex: Int? = null) {
    val isEnd: Boolean get() = candidate.isEmpty()
}

enum class PeerState { New, Connecting, Connected, Disconnected, Failed, Closed }

sealed interface PeerEvent {
    data class LocalCandidate(val candidate: IceCandidate) : PeerEvent
    data class StateChanged(val state: PeerState) : PeerEvent
}

/**
 * Một RTCPeerConnection, bọc mỏng. Cài bằng thư viện WebRTC của từng nền tảng. [events] phải là luồng NÓNG (không phát lại),
 * phát candidate ngay khi có (trickle) và mọi thay đổi trạng thái kết nối. Thứ tự gọi do [CallSession] lo.
 */
interface RtcPeer {
    val events: Flow<PeerEvent>
    suspend fun createOffer(iceRestart: Boolean = false): SessionDescription
    suspend fun createAnswer(): SessionDescription
    suspend fun setLocalDescription(description: SessionDescription)
    suspend fun setRemoteDescription(description: SessionDescription)
    /** Thư viện thật ném lỗi nếu gọi trước [setRemoteDescription]; [CallSession] giữ lại candidate đến sớm. */
    suspend fun addIceCandidate(candidate: IceCandidate)
    fun close()
}

// ---------------------------------------------------------------- kênh báo hiệu (MQTT, WebSocket, REST… do app chọn)

sealed interface SignalMessage {
    data class Description(val description: SessionDescription) : SignalMessage
    data class Candidate(val candidate: IceCandidate) : SignalMessage
    /** Bên kia kết thúc; [reason] là mã ngắn (vd "insecure_sdp", "hangup"). */
    data class Bye(val reason: String) : SignalMessage
}

/** Đường báo hiệu của MỘT cuộc gọi. App chuyển đổi sang khuôn thông điệp của mình (vd khung header+msg của appkit-iot). */
interface Signaling {
    val incoming: Flow<SignalMessage>
    suspend fun send(message: SignalMessage)
}

// ---------------------------------------------------------------- các bước nối cuộc gọi

enum class CallRole {
    /** Bên tạo offer (người xem). Lo cả ICE restart. */
    Caller,
    /** Bên trả answer (camera). */
    Callee,
}

/** Vì sao cuộc gọi kết thúc. [code] khớp trường `error` của báo cáo phiên. */
enum class CallEnd(val code: String) {
    NoOffer("no_offer"), NoAnswer("no_answer"), IceTimeout("timeout"), NoPath("no_path"),
    InsecureSdp("insecure_sdp"), RemoteHangup("closed"), LocalHangup("closed"), Error("other"),
}

sealed interface CallState {
    data object Idle : CallState
    /** Đang trao offer/answer. */
    data object Signaling : CallState
    /** Đã có answer, chờ ICE nối. */
    data object Connecting : CallState
    data object Connected : CallState
    /** Mất kết nối, đang thử lại (bên gọi: ICE restart lần [attempt]). */
    data class Reconnecting(val attempt: Int) : CallState
    data class Ended(val reason: CallEnd, val detail: String? = null) : CallState
}

data class CallTimeouts(
    /** Gửi offer → nhận answer (và bên nhận: chờ offer đầu tiên). */
    val answer: Duration = 10.seconds,
    /** Có answer → ICE `Connected`. */
    val ice: Duration = 20.seconds,
    /** `Disconnected` thường tự hết (đổi sóng ngắn); chờ chừng này rồi mới ICE restart. `Failed` thì restart ngay. */
    val disconnectedGrace: Duration = 3.seconds,
    /** Số lần ICE restart liên tiếp trước khi bỏ (`NoPath`). */
    val maxRestarts: Int = 2,
)

/**
 * Các bước nối MỘT cuộc gọi, theo đúng thứ tự WebRTC đòi hỏi, chung cho mọi nền tảng:
 *  - Bên gọi: tạo offer → gửi → chờ answer ([CallTimeouts.answer]) → chờ ICE ([CallTimeouts.ice]).
 *    Đang gọi mà rớt: chờ [CallTimeouts.disconnectedGrace], gọi [beforeIceRestart] (vd xin lại TURN), tạo offer `iceRestart`.
 *  - Bên nhận: chờ offer → kiểm DTLS → trả answer; offer mới giữa chừng (ICE restart) thì trả answer mới.
 *  - Trickle: candidate gửi ngay khi có, nhưng chỉ SAU khi mô tả của mình đã đi; candidate đến trước mô tả của bên kia được giữ lại.
 *  - SDP thiếu DTLS fingerprint bị từ chối (gửi Bye "insecure_sdp").
 */
class CallSession(
    private val role: CallRole,
    private val peer: RtcPeer,
    private val signaling: Signaling,
    private val scope: CoroutineScope,
    private val timeouts: CallTimeouts = CallTimeouts(),
    private val beforeIceRestart: suspend () -> Unit = {},
    private val name: String = "call",
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** Thời gian từ [start] tới lần `Connected` đầu tiên; `null` nếu chưa nối được. */
    var setupTime: Duration? = null
        private set

    private val lock = Mutex()
    private val peerState = MutableStateFlow(PeerState.New)
    private var remoteSet = false
    private var localSent = false
    private val pendingRemote = mutableListOf<IceCandidate>()
    private val pendingLocal = mutableListOf<IceCandidate>()
    private var awaitingAnswer: CompletableDeferred<Unit>? = null
    private val firstOffer = CompletableDeferred<Unit>()
    private var job: Job? = null
    private var started = timeSource.markNow()

    fun start(): Job {
        job?.let { return it }
        started = timeSource.markNow()
        return scope.launch {
            try {
                coroutineScope {
                    // UNDISPATCHED: đăng ký nghe NGAY, trước createOffer/setLocalDescription. Luồng sự kiện là luồng nóng:
                    // candidate phát ra trước khi có người nghe sẽ mất hẳn và cuộc gọi không bao giờ nối được.
                    launch(start = CoroutineStart.UNDISPATCHED) { peer.events.collect { onPeerEvent(it) } }
                    launch(start = CoroutineStart.UNDISPATCHED) { signaling.incoming.collect { onSignal(it) } }
                    if (role == CallRole.Caller) runCaller() else runCallee()
                }
            } catch (e: EndCall) {
                finish(e.reason, e.detail)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finish(CallEnd.LocalHangup, null)
                throw e
            } catch (e: Throwable) {
                AppLog.w("webrtc", "$name failed: ${e::class.simpleName}: ${e.message}")
                finish(CallEnd.Error, e.message)
            }
        }.also { job = it }
    }

    /** Kết thúc có chủ đích: báo bên kia (nếu được) rồi đóng. */
    suspend fun hangup(reason: String = "hangup") {
        if (_state.value is CallState.Ended) return
        runCatching { signaling.send(SignalMessage.Bye(reason)) }
        finish(CallEnd.LocalHangup, reason)
        job?.cancel()
    }

    // ---------------------------------------------------------------- bên gọi

    private suspend fun runCaller() {
        _state.value = CallState.Signaling
        negotiate(iceRestart = false) ?: throw EndCall(CallEnd.NoAnswer)
        _state.value = CallState.Connecting
        if (!awaitConnected()) throw EndCall(CallEnd.IceTimeout)
        markConnected()
        var restarts = 0
        while (true) {
            val bad = peerState.first { it == PeerState.Disconnected || it == PeerState.Failed }
            if (bad == PeerState.Disconnected) {
                val recovered = withTimeoutOrNull(timeouts.disconnectedGrace) { peerState.first { it == PeerState.Connected } } != null
                if (recovered) continue
            }
            while (true) {
                if (restarts >= timeouts.maxRestarts) throw EndCall(CallEnd.NoPath, "gave up after $restarts ice restarts")
                restarts++
                _state.value = CallState.Reconnecting(restarts)
                AppLog.i("webrtc", "$name ice restart $restarts")
                beforeIceRestart()
                if (negotiate(iceRestart = true) != null && awaitConnected()) break
            }
            restarts = 0
            _state.value = CallState.Connected
        }
    }

    /** Tạo + gửi offer, chờ answer. `null` nếu hết giờ. */
    private suspend fun negotiate(iceRestart: Boolean): Unit? {
        val answered = CompletableDeferred<Unit>()
        val offer = peer.createOffer(iceRestart)
        if (!Sdp.hasDtlsFingerprint(offer.sdp)) throw EndCall(CallEnd.InsecureSdp, "local offer has no dtls fingerprint")
        lock.withLock { localSent = false; remoteSet = false; pendingRemote.clear(); awaitingAnswer = answered }
        peer.setLocalDescription(offer)
        signaling.send(SignalMessage.Description(offer))
        flushLocal()
        return withTimeoutOrNull(timeouts.answer) { answered.await() }
    }

    // ---------------------------------------------------------------- bên nhận

    private suspend fun runCallee() {
        _state.value = CallState.Signaling
        withTimeoutOrNull(timeouts.answer) { firstOffer.await() } ?: throw EndCall(CallEnd.NoOffer)
        _state.value = CallState.Connecting
        if (!awaitConnected()) throw EndCall(CallEnd.IceTimeout)
        markConnected()
        while (true) {   // bên nhận không tự restart: chờ bên gọi gửi offer mới; quá lâu thì bỏ
            peerState.first { it == PeerState.Disconnected || it == PeerState.Failed }
            _state.value = CallState.Reconnecting(0)
            val window = timeouts.disconnectedGrace + (timeouts.answer + timeouts.ice) * (timeouts.maxRestarts + 1)
            withTimeoutOrNull(window) { peerState.first { it == PeerState.Connected } } ?: throw EndCall(CallEnd.NoPath)
            _state.value = CallState.Connected
        }
    }

    private suspend fun answerOffer(offer: SessionDescription) {
        if (!Sdp.hasDtlsFingerprint(offer.sdp)) {
            runCatching { signaling.send(SignalMessage.Bye("insecure_sdp")) }
            throw EndCall(CallEnd.InsecureSdp, "remote offer has no dtls fingerprint")
        }
        peer.setRemoteDescription(offer)
        val early = lock.withLock { remoteSet = true; localSent = false; pendingRemote.toList().also { pendingRemote.clear() } }
        early.forEach { addRemote(it) }
        val answer = peer.createAnswer()
        peer.setLocalDescription(answer)
        signaling.send(SignalMessage.Description(answer))
        flushLocal()
        firstOffer.complete(Unit)
    }

    // ---------------------------------------------------------------- chung

    private suspend fun onSignal(m: SignalMessage) {
        when (m) {
            is SignalMessage.Bye -> throw EndCall(CallEnd.RemoteHangup, m.reason)
            is SignalMessage.Candidate -> {
                val now = lock.withLock { if (remoteSet) true else { pendingRemote += m.candidate; false } }
                if (now) addRemote(m.candidate)
            }
            is SignalMessage.Description -> when {
                role == CallRole.Callee && m.description.type == SdpType.Offer -> answerOffer(m.description)
                role == CallRole.Caller && m.description.type == SdpType.Answer -> {
                    val waiting = lock.withLock { awaitingAnswer } ?: return   // answer lạc (đã hết giờ): bỏ
                    if (!Sdp.hasDtlsFingerprint(m.description.sdp)) {
                        runCatching { signaling.send(SignalMessage.Bye("insecure_sdp")) }
                        throw EndCall(CallEnd.InsecureSdp, "remote answer has no dtls fingerprint")
                    }
                    peer.setRemoteDescription(m.description)
                    val early = lock.withLock { remoteSet = true; awaitingAnswer = null; pendingRemote.toList().also { pendingRemote.clear() } }
                    early.forEach { addRemote(it) }
                    waiting.complete(Unit)
                }
                else -> AppLog.w("webrtc", "$name ignored ${m.description.type} as $role")
            }
        }
    }

    private suspend fun onPeerEvent(e: PeerEvent) {
        when (e) {
            is PeerEvent.StateChanged -> peerState.value = e.state
            is PeerEvent.LocalCandidate -> {
                val now = lock.withLock { if (localSent) true else { pendingLocal += e.candidate; false } }
                if (now) signaling.send(SignalMessage.Candidate(e.candidate))
            }
        }
    }

    /** Candidate hỏng hay thuộc lần nối trước (ICE restart) bị thư viện từ chối: bỏ qua, không làm rơi cuộc gọi. */
    private suspend fun addRemote(c: IceCandidate) {
        runCatching { peer.addIceCandidate(c) }.onFailure { AppLog.w("webrtc", "$name dropped remote candidate: ${it.message}") }
    }

    /** Mô tả của mình đã đi: gửi các candidate đã giữ, từ đây gửi thẳng. */
    private suspend fun flushLocal() {
        val held = lock.withLock { localSent = true; pendingLocal.toList().also { pendingLocal.clear() } }
        held.forEach { signaling.send(SignalMessage.Candidate(it)) }
    }

    private suspend fun awaitConnected(): Boolean =
        withTimeoutOrNull(timeouts.ice) { peerState.first { it == PeerState.Connected } } != null

    private fun markConnected() {
        if (setupTime == null) setupTime = started.elapsedNow()
        _state.value = CallState.Connected
    }

    private suspend fun finish(reason: CallEnd, detail: String?) {
        if (_state.value is CallState.Ended) return
        _state.value = CallState.Ended(reason, detail)
        withContext(NonCancellable) { runCatching { peer.close() } }
        AppLog.i("webrtc", "$name ended: ${reason.code}${detail?.let { " ($it)" } ?: ""}")
    }

    private class EndCall(val reason: CallEnd, val detail: String? = null) : Exception(reason.code)
}

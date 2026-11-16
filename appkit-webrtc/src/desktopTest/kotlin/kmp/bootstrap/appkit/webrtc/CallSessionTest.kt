package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Thời gian ẢO (runTest): các hạn 10–20 giây chạy tức thì và đo được chính xác. */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionTest {
    private class Call(val caller: CallSession, val callee: CallSession, val viewer: FakePeer, val camera: FakePeer,
                       val toCamera: FakeSignaling, val toViewer: FakeSignaling, val net: FakeNetwork)

    private fun TestScope.call(
        net: FakeNetwork = FakeNetwork(), viewer: FakePeer = FakePeer(net), camera: FakePeer = FakePeer(net),
        restarts: MutableList<Int>? = null, startCallee: Boolean = true,
    ): Call {
        val (v, c) = FakeSignaling.pair()
        val caller = CallSession(CallRole.Caller, viewer, v, backgroundScope, beforeIceRestart = { restarts?.add(1) }, name = "viewer", timeSource = testScheduler.timeSource)
        val callee = CallSession(CallRole.Callee, camera, c, backgroundScope, name = "camera", timeSource = testScheduler.timeSource)
        if (startCallee) callee.start()
        caller.start()
        return Call(caller, callee, viewer, camera, v, c, net)
    }

    private fun offers(s: FakeSignaling) = s.sent.filterIsInstance<SignalMessage.Description>().filter { it.description.type == SdpType.Offer }

    @Test fun connectsAndSendsCandidatesOnlyAfterTheDescription() = runTest {
        val c = call()
        c.caller.state.first { it == CallState.Connected }
        c.callee.state.first { it == CallState.Connected }
        assertIs<SignalMessage.Description>(c.toCamera.sent.first(), "offer đi trước mọi candidate")
        assertIs<SignalMessage.Description>(c.toViewer.sent.first(), "answer đi trước mọi candidate")
        assertTrue(c.toCamera.sent.any { it is SignalMessage.Candidate && it.candidate.isEnd }, "báo hết candidate")
        assertEquals(listOf("createOffer(restart=false)", "setLocal(Offer)", "setRemote(Answer)"), c.viewer.log.filterNot { it == "addIce" })
        assertEquals(0.seconds, c.caller.setupTime)                                  // peer giả nối ngay, thời gian ảo
    }

    @Test fun slowSignalingStillSendsTheDescriptionFirst() = runTest {
        // Gửi offer/answer mất 500 ms: trong lúc đó peer đã phát candidate. Chúng phải đợi, không được chen lên trước.
        val net = FakeNetwork()
        val (v, c) = FakeSignaling.pair()
        listOf(v, c).forEach { s -> s.beforeSend = { if (it is SignalMessage.Description) kotlinx.coroutines.delay(500) } }
        val caller = CallSession(CallRole.Caller, FakePeer(net), v, backgroundScope)
        val callee = CallSession(CallRole.Callee, FakePeer(net), c, backgroundScope)
        callee.start(); caller.start()
        caller.state.first { it == CallState.Connected }; callee.state.first { it == CallState.Connected }
        assertIs<SignalMessage.Description>(v.sent.first()); assertIs<SignalMessage.Description>(c.sent.first())
    }

    @Test fun candidatesArrivingBeforeTheOfferAreHeld() = runTest {
        // Mạng đảo thứ tự: giữ offer lại, cho các candidate đi trước. Peer giả ném lỗi nếu nhận candidate trước SDP.
        var held: SignalMessage? = null
        val c = call(startCallee = false)
        c.toCamera.route = { m ->
            when {
                m is SignalMessage.Description -> { held = m; emptyList() }
                m is SignalMessage.Candidate && m.candidate.isEnd -> listOf(m, held!!)
                else -> listOf(m)
            }
        }
        c.callee.start()
        c.callee.state.first { it == CallState.Connected }
        assertEquals("setRemote(Offer)", c.camera.log.first(), "candidate sớm được giữ, không đưa vào peer trước SDP")
    }

    @Test fun noAnswerEndsAfterTenSeconds() = runTest {
        val c = call(startCallee = false)
        val end = c.caller.state.first { it is CallState.Ended } as CallState.Ended
        assertEquals(CallEnd.NoAnswer, end.reason); assertEquals("no_answer", end.reason.code)
        assertEquals(10_000, testScheduler.currentTime)
        assertTrue(c.viewer.closed)
    }

    @Test fun calleeWithoutOfferEndsAfterTenSeconds() = runTest {
        val (_, cam) = FakeSignaling.pair()
        val callee = CallSession(CallRole.Callee, FakePeer(FakeNetwork()), cam, backgroundScope).apply { start() }
        assertEquals(CallEnd.NoOffer, (callee.state.first { it is CallState.Ended } as CallState.Ended).reason)
        assertEquals(10_000, testScheduler.currentTime)
    }

    @Test fun noIcePathEndsAfterTwentySeconds() = runTest {
        val c = call(net = FakeNetwork().apply { pathOpen = false })
        assertEquals(CallEnd.IceTimeout, (c.caller.state.first { it is CallState.Ended } as CallState.Ended).reason)
        assertEquals(20_000, testScheduler.currentTime)
    }

    @Test fun offerWithoutDtlsIsRefusedByTheCallee() = runTest {
        val net = FakeNetwork()
        val c = call(net, viewer = FakePeer(net), startCallee = true)
        c.caller.state.first { it == CallState.Connected }
        // bên gọi giả mạo/hỏng: offer không có fingerprint
        val (_, cam) = FakeSignaling.pair()
        val callee = CallSession(CallRole.Callee, FakePeer(net), cam, backgroundScope).apply { start() }
        cam.inject(SignalMessage.Description(SessionDescription(SdpType.Offer, "v=0\r\na=ice-ufrag:g1\r\n")))
        assertEquals(CallEnd.InsecureSdp, (callee.state.first { it is CallState.Ended } as CallState.Ended).reason)
        assertEquals(SignalMessage.Bye("insecure_sdp"), cam.sent.single())
    }

    @Test fun answerWithoutDtlsIsRefusedByTheCaller() = runTest {
        val net = FakeNetwork()
        val c = call(net, camera = FakePeer(net, withFingerprint = false))
        assertEquals(CallEnd.InsecureSdp, (c.caller.state.first { it is CallState.Ended } as CallState.Ended).reason)
        assertEquals(CallEnd.RemoteHangup, (c.callee.state.first { it is CallState.Ended } as CallState.Ended).reason)
        assertTrue(c.viewer.log.none { it.startsWith("setRemote") }, "answer không an toàn không được đưa vào peer")
    }

    @Test fun ownOfferWithoutDtlsIsNeverSent() = runTest {
        val net = FakeNetwork()
        val c = call(net, viewer = FakePeer(net, withFingerprint = false))
        assertEquals(CallEnd.InsecureSdp, (c.caller.state.first { it is CallState.Ended } as CallState.Ended).reason)
        assertTrue(c.toCamera.sent.none { it is SignalMessage.Description })
    }

    @Test fun shortBlipRecoversWithoutIceRestart() = runTest {
        val restarts = mutableListOf<Int>()
        val c = call(restarts = restarts)
        c.caller.state.first { it == CallState.Connected }
        c.viewer.emitState(PeerState.Disconnected); runCurrent()
        advanceTimeBy(2_000); c.viewer.emitState(PeerState.Connected); runCurrent()   // tự lành trong 3 giây
        advanceTimeBy(10_000); runCurrent()
        assertEquals(CallState.Connected, c.caller.state.value)
        assertEquals(1, offers(c.toCamera).size); assertTrue(restarts.isEmpty())
    }

    @Test fun failedPathTriggersIceRestartAndReconnects() = runTest {
        val restarts = mutableListOf<Int>()
        val c = call(restarts = restarts)
        c.caller.state.first { it == CallState.Connected }
        c.viewer.emitState(PeerState.Failed); c.camera.emitState(PeerState.Failed)        // đổi Wi-Fi → 4G
        c.caller.state.first { it == CallState.Reconnecting(1) }
        c.caller.state.first { it == CallState.Connected }
        c.callee.state.first { it == CallState.Connected }
        assertEquals(1, restarts.size, "xin lại TURN trước khi restart")
        assertEquals(listOf(false, true), offers(c.toCamera).map { Regex("g(\\d+)").find(it.description.sdp)!!.groupValues[1] != "1" })
        assertTrue("createOffer(restart=true)" in c.viewer.log)
    }

    @Test fun restartsAreBoundedThenTheCallEnds() = runTest {
        val restarts = mutableListOf<Int>()
        val c = call(restarts = restarts)
        c.caller.state.first { it == CallState.Connected }
        c.net.pathOpen = false
        c.viewer.emitState(PeerState.Failed); c.camera.emitState(PeerState.Failed)
        val end = c.caller.state.first { it is CallState.Ended } as CallState.Ended
        assertEquals(CallEnd.NoPath, end.reason); assertEquals(2, restarts.size)
        assertEquals(3, offers(c.toCamera).size)
    }

    @Test fun hangupReachesTheOtherSideAndClosesBothPeers() = runTest {
        val c = call()
        c.caller.state.first { it == CallState.Connected }
        c.caller.hangup()
        assertEquals(CallEnd.LocalHangup, (c.caller.state.value as CallState.Ended).reason)
        assertEquals(CallEnd.RemoteHangup, (c.callee.state.first { it is CallState.Ended } as CallState.Ended).reason)
        runCurrent()
        assertTrue(c.viewer.closed && c.camera.closed)
    }
}

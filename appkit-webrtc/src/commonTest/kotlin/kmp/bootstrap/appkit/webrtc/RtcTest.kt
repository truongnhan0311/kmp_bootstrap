package kmp.bootstrap.appkit.webrtc

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RtcTest {
    private val c1 = IceCandidate("candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host", "0", 0)
    private val c2 = IceCandidate("candidate:2 1 udp 1686052607 1.2.3.4 50001 typ srflx", "0", 0)

    @Test fun candidatesBeforeRemoteDescriptionAreQueuedInOrder() = runTest {
        val peer = FakeRtcEngine().createPeer(emptyList())
        val queue = CandidateQueue(peer)
        queue.add(c1); queue.add(c2)
        assertEquals(2, queue.pending()); assertTrue(peer.addedCandidates.isEmpty())
        peer.setRemoteDescription(SessionDescription(SdpType.Offer, "v=0\r\n"))
        queue.remoteDescriptionSet()
        assertEquals(listOf(c1, c2), peer.addedCandidates)
        val c3 = c2.copy(candidate = "")
        queue.add(c3)                                   // sau khi sẵn sàng: đưa vào ngay
        assertEquals(listOf(c1, c2, c3), peer.addedCandidates); assertEquals(0, queue.pending())
    }

    @Test fun replaceTrackSwapsWithoutRenegotiation() = runTest {
        val engine = FakeRtcEngine()
        val peer = engine.createPeer(emptyList())
        val audio = peer.addTransceiver(MediaKind.Audio, Direction.SendRecv) as FakeTransceiver
        val offerBefore = peer.createOffer().sdp
        val mic = engine.createMicrophoneTrack()
        audio.replaceTrack(mic)                          // talkback bật
        assertSame(mic, audio.track)
        audio.replaceTrack(null)                         // talkback tắt
        assertNull(audio.track); assertEquals(2, audio.replaceCount)
        assertEquals(offerBefore, peer.createOffer().sdp, "SDP unchanged: no renegotiation needed")
        assertEquals("sendrecv", SdpTools.direction(offerBefore, "audio"))
    }

    @Test fun dataChannelSendAndReceive() = runTest {
        val ch = FakeRtcEngine().createPeer(emptyList()).createDataChannel("ctrl") as FakeDataChannel
        assertTrue(ch.send("""{"v":1,"t":"video.start"}"""))
        val got = async { ch.messages.first() }
        runCurrent()
        ch.receive("""{"v":1,"t":"status"}""")
        assertEquals(ChannelMessage.Text("""{"v":1,"t":"status"}"""), got.await())
        ch.close()
        assertFalse(ch.send("x"))
        assertEquals(listOf("""{"v":1,"t":"video.start"}"""), ch.sent)
    }

    @Test fun peerEventsAndClose() = runTest {
        val peer = FakeRtcEngine().createPeer(listOf(IceServer(listOf("turn:turn.example.com:3478?transport=udp"), "1700000000:s1", "c2VjcmV0")))
        val ev = async { peer.events.first { it is PeerEvent.State && it.state == PeerState.Closed } }
        runCurrent()
        peer.emit(PeerEvent.LocalCandidate(c1))
        peer.close()
        ev.await()
        assertTrue(peer.closed)
        assertEquals("1700000000:s1", peer.iceServers.single().username)
    }
}

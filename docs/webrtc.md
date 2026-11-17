# appkit-webrtc — nối cuộc gọi WebRTC (phần chung)

Phần **không phụ thuộc thư viện WebRTC** của một cuộc gọi: máy chủ STUN/TURN có hạn, xử lý SDP, và các bước offer/answer/candidate
với hết giờ và ICE restart. Chạy trên mọi nền tảng, kiểm hết trên JVM. Thư viện WebRTC thật của Android/iOS gắn vào qua interface `RtcPeer`.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-webrtc") }
```

## Máy chủ ICE có hạn

TURN REST (`username = "<hết hạn>:<định danh>"`, `credential = base64(HMAC-SHA1(secret, username))`): hạn đọc được từ username.
`IceConfigSource` chỉ gọi server khi chưa có hoặc còn dưới 1 giờ; nhiều nơi gọi cùng lúc chỉ xin một lần.
Username có dấu `:` nên **luôn** đưa thành trường riêng; URL có `user:pass@` bị từ chối.

<!-- snippet: webrtc-ice -->
```kotlin
// Thông tin TURN do server cấp (TURN REST): hạn nằm trong username, đưa cho thư viện thành TRƯỜNG RIÊNG.
val source = IceConfigSource(fetch = {
    IceConfig.of(
        listOf(IceServer(listOf("stun:stun.example.com:3478"))),
        listOf(IceServer(listOf("turn:relay.example.com:3478?transport=udp", "turns:relay.example.com:443?transport=tcp"),
            username = "4102444800:session-1", credential = "base64-hmac")),
    )
})
val ice = source.get()               // chỉ gọi server khi chưa có hoặc còn dưới 1 giờ
```
<!-- /snippet -->

## SDP

<!-- snippet: webrtc-sdp -->
```kotlin
require(Sdp.hasDtlsFingerprint(offerSdp))                                   // thiếu DTLS → từ chối
var sdp = Sdp.preferCodec(offerSdp, "H264") { "profile-level-id=42e01f" in it }   // chip mã hoá của điện thoại
sdp = Sdp.limitBitrate(sdp, kbps = 1200)                                    // theo gói hoặc sức máy camera
println(Sdp.redact(sdp))                                                    // ghi log: không IP, không ufrag/pwd
```
<!-- /snippet -->

| Hàm | Làm gì |
|---|---|
| `hasDtlsFingerprint` | có `a=fingerprint:` không rỗng (DTLS-SRTP) |
| `preferCodec(sdp, "H264") { fmtp }` | đưa codec (kèm gói RTX của nó) lên đầu phần `m=video`; chỉ sắp lại, không xoá codec nào |
| `limitBitrate(sdp, kbps)` | `b=AS` + `b=TIAS` sau dòng `c=`, thay giá trị cũ |
| `redact(sdp)` | bản để ghi log: giữ `m=`, codec, hướng media, loại candidate; bỏ IP, cổng, ufrag/pwd, dấu vân tay |
| `IceCandidateInfo.parse` | giao thức, địa chỉ, cổng, loại (`host`/`srflx`/`relay`) của một candidate |

## Nối cuộc gọi

<!-- snippet: webrtc-call -->
```kotlin
val call = CallSession(
    CallRole.Caller, viewerPeer, signaling, scope,
    timeouts = CallTimeouts(answer = 10.seconds, ice = 20.seconds),     // mặc định đã đúng như vậy
    beforeIceRestart = { source.invalidate(); source.get() },           // đổi mạng giữa chừng: xin TURN mới rồi mới restart
)
call.start()
when (val s = call.state.first { it == CallState.Connected || it is CallState.Ended }) {
    CallState.Connected -> println("nối sau ${call.setupTime}")
    is CallState.Ended -> println("thất bại: ${s.reason.code}")         // no_answer, timeout, no_path, insecure_sdp…
    else -> Unit
}
call.hangup()                                                           // báo bên kia, đóng peer
```
<!-- /snippet -->

| Tình huống | `CallSession` làm |
|---|---|
| Bên gọi (`Caller`) | tạo offer → gửi → chờ answer **10 giây** (`no_answer`) → chờ ICE **20 giây** (`timeout`) |
| Bên nhận (`Callee`) | chờ offer 10 giây (`no_offer`) → kiểm DTLS → trả answer; offer mới giữa chừng (ICE restart) thì trả answer mới |
| Candidate của mình | gửi ngay khi có (trickle), nhưng chỉ **sau** offer/answer của mình |
| Candidate của bên kia đến trước SDP | giữ lại, đưa vào peer sau khi có SDP (thư viện thật ném lỗi nếu đưa sớm) |
| Candidate của lần nối trước (sau ICE restart) | bỏ qua, không làm rơi cuộc gọi |
| SDP thiếu DTLS fingerprint | không đưa vào peer, gửi `Bye("insecure_sdp")`, kết thúc `insecure_sdp` |
| `Disconnected` (chập chờn) | chờ 3 giây cho tự lành |
| Không lành, hoặc `Failed` (đổi Wi-Fi ↔ 4G) | bên gọi chạy `beforeIceRestart` (xin TURN mới) rồi ICE restart, tối đa 2 lần, hết thì `no_path` |
| `hangup()` / `Bye` từ bên kia | báo bên kia, đóng peer; trạng thái `Ended(LocalHangup / RemoteHangup)` |

`CallEnd.code` khớp trường `error` của báo cáo phiên (`no_answer`, `timeout`, `no_path`, `closed`, `other`…). `setupTime` là thời gian đến lần nối đầu.

## Cài `RtcPeer` cho một nền tảng

`events` phải là luồng **nóng không phát lại**, phát mọi candidate (kể cả candidate rỗng = hết) và mọi đổi trạng thái kết nối.
`CallSession` đăng ký nghe trước khi gọi `createOffer`, nên candidate phát ngay trong `setLocalDescription` không bị mất.
`addIceCandidate` được phép ném lỗi như thư viện thật.

## Peer thật: Android

`AndroidRtcPeer` trên libwebrtc của Google (`io.getstream:stream-webrtc-android`, API `org.webrtc`), có sẵn khi khai `appkit-webrtc`.

```kotlin
val rtc = AndroidRtc.get(context)                                   // một lần: EGL + PeerConnectionFactory
val cam = AndroidRtcPeer(rtc, ice.servers, PeerMedia.SendCamera())  // điện thoại làm camera: camera sau 1280×720@30 + micro
val viewer = AndroidRtcPeer(rtc, ice.servers, PeerMedia.Receive())  // người xem: chỉ nhận, có DataChannel "ctrl"
val renderer = rtc.newRenderer(context)                             // View để vẽ (AndroidView trong Compose)
viewer.remoteVideo.collect { it?.addSink(renderer) }                // cam.localVideo?.addSink(…) để xem trước
cam.setVideoQuality(640, 480, 24)                                   // hạ bậc khi nóng/mạng yếu, không cần đàm phán lại
```

- Cần quyền `CAMERA` (và `RECORD_AUDIO` nếu có tiếng) **trước** khi dựng `SendCamera`.
- Phía camera (bên trả answer) thêm track bằng `addTrack`, không bằng `addTransceiver`: khi áp offer, chuẩn JSEP chỉ ghép m-line vào transceiver
  sinh từ `addTrack`; dùng `addTransceiver` thì answer có m-line `inactive` và hình không đi.
- Username TURN REST có dấu `:` nên đưa vào `setUsername`/`setPassword`, không ghép vào URL.

## Peer thật: iOS

libwebrtc dùng từ Swift (gói [`WebRTC`](https://github.com/stasel/WebRTC) qua Swift Package Manager). Kotlin có `IosRtcPeer` bọc hai
giao diện `SwiftRtcFactory`/`SwiftRtcPeer`; app chép lớp Swift dưới đây vào `iosApp` và gán lúc khởi động
`IosRtc.shared.factory = SwiftRtcFactory()`. Info.plist cần `NSCameraUsageDescription`, `NSMicrophoneUsageDescription`.

```swift
import WebRTC
import ComposeApp   // framework Kotlin của app

final class SwiftRtcFactory: NSObject, kmp.bootstrap.appkit.webrtc.SwiftRtcFactory {
    static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()
    func create(iceJson: String, sendCamera: Bool, listener: SwiftRtcListener) -> SwiftRtcPeer {
        SwiftRtcPeerImpl(iceJson: iceJson, sendCamera: sendCamera, listener: listener)
    }
}

final class SwiftRtcPeerImpl: NSObject, SwiftRtcPeer, RTCPeerConnectionDelegate {
    private let listener: SwiftRtcListener
    private var pc: RTCPeerConnection!
    private var capturer: RTCCameraVideoCapturer?
    private var localTrack: RTCVideoTrack?
    private var remoteTrack: RTCVideoTrack?
    private var useFront = false
    private lazy var view: RTCMTLVideoView = { let v = RTCMTLVideoView(); v.videoContentMode = .scaleAspectFit; return v }()

    init(iceJson: String, sendCamera: Bool, listener: SwiftRtcListener) {
        self.listener = listener
        super.init()
        let f = SwiftRtcFactory.factory
        let config = RTCConfiguration()
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        config.bundlePolicy = .maxBundle
        config.rtcpMuxPolicy = .require
        let items = (try? JSONSerialization.jsonObject(with: Data(iceJson.utf8))) as? [[String: Any]] ?? []
        config.iceServers = items.map {   // username/credential là trường riêng (username TURN REST có dấu ':')
            RTCIceServer(urlStrings: $0["urls"] as? [String] ?? [], username: $0["username"] as? String, credential: $0["credential"] as? String)
        }
        pc = f.peerConnection(with: config, constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: self)
        if sendCamera {
            let source = f.videoSource()
            capturer = RTCCameraVideoCapturer(delegate: source)
            let track = f.videoTrack(with: source, trackId: "video0")
            localTrack = track
            pc.add(track, streamIds: ["cam"])          // addTrack: bên trả answer (xem phần Android)
            pc.add(f.audioTrack(with: f.audioSource(with: nil), trackId: "audio0"), streamIds: ["cam"])
            startCapture()
            track.add(view)
        } else {
            let recv = RTCRtpTransceiverInit(); recv.direction = .recvOnly
            pc.addTransceiver(of: .video, init: recv)
            pc.addTransceiver(of: .audio, init: recv)
            let dc = RTCDataChannelConfiguration(); dc.isOrdered = true
            _ = pc.dataChannel(forLabel: "ctrl", configuration: dc)
        }
    }

    private func startCapture() {
        guard let cap = capturer,
              let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == (useFront ? .front : .back) })
                ?? RTCCameraVideoCapturer.captureDevices().first else { return }
        let format = RTCCameraVideoCapturer.supportedFormats(for: device)
            .min(by: { abs(CMVideoFormatDescriptionGetDimensions($0.formatDescription).width - 1280) < abs(CMVideoFormatDescriptionGetDimensions($1.formatDescription).width - 1280) })
        guard let fmt = format else { return }
        cap.startCapture(with: device, format: fmt, fps: 30)
    }

    private func sdpType(_ t: String) -> RTCSdpType { t == "offer" ? .offer : .answer }

    func createOffer(iceRestart: Bool, done: @escaping (String?, String?) -> Void) {
        let c = RTCMediaConstraints(mandatoryConstraints: iceRestart ? ["IceRestart": "true"] : nil, optionalConstraints: nil)
        pc.offer(for: c) { sdp, err in done(sdp?.sdp, err?.localizedDescription ?? (sdp == nil ? "offer failed" : nil)) }
    }
    func createAnswer(done: @escaping (String?, String?) -> Void) {
        pc.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { sdp, err in
            done(sdp?.sdp, err?.localizedDescription ?? (sdp == nil ? "answer failed" : nil))
        }
    }
    func setLocal(type: String, sdp: String, done: @escaping (String?) -> Void) {
        pc.setLocalDescription(RTCSessionDescription(type: sdpType(type), sdp: sdp)) { done($0?.localizedDescription) }
    }
    func setRemote(type: String, sdp: String, done: @escaping (String?) -> Void) {
        pc.setRemoteDescription(RTCSessionDescription(type: sdpType(type), sdp: sdp)) { done($0?.localizedDescription) }
    }
    func addCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int32, done: @escaping (String?) -> Void) {
        pc.add(RTCIceCandidate(sdp: candidate, sdpMLineIndex: sdpMLineIndex, sdpMid: sdpMid)) { done($0?.localizedDescription) }
    }
    func videoView() -> UIView? { view }
    func switchCamera() { useFront.toggle(); capturer?.stopCapture { [weak self] in self?.startCapture() } }
    func close() { capturer?.stopCapture(); pc.close() }

    // RTCPeerConnectionDelegate
    func peerConnection(_ pc: RTCPeerConnection, didGenerate c: RTCIceCandidate) {
        listener.onCandidate(candidate: c.sdp, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex)
    }
    func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCIceGatheringState) { if s == .complete { listener.onGatheringComplete() } }
    func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCPeerConnectionState) {
        let names: [RTCPeerConnectionState: String] = [.new: "new", .connecting: "connecting", .connected: "connected",
                                                        .disconnected: "disconnected", .failed: "failed", .closed: "closed"]
        listener.onConnectionState(state: names[s] ?? "new")
    }
    func peerConnection(_ pc: RTCPeerConnection, didStartReceivingOn t: RTCRtpTransceiver) {
        if let track = t.receiver.track as? RTCVideoTrack { DispatchQueue.main.async { self.remoteTrack = track; track.add(self.view) } }
    }
    func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCSignalingState) {}
    func peerConnection(_ pc: RTCPeerConnection, didAdd s: RTCMediaStream) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove s: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
    func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCIceConnectionState) {}
    func peerConnection(_ pc: RTCPeerConnection, didRemove c: [RTCIceCandidate]) {}
    func peerConnection(_ pc: RTCPeerConnection, didOpen dc: RTCDataChannel) {}
}
```

Trong Compose (iosMain), vẽ bằng `UIKitView(factory = { peer.swift.videoView()!! })`.

## Đã kiểm / chưa kiểm

- **JVM, mỗi lần đổi code:** mọi hàng trong bảng trên, bằng peer giả theo luật của RTCPeerConnection thật và kênh báo hiệu giả có thể đảo thứ tự hay chậm;
  thời gian ảo nên các hạn 10–20 giây được đo đúng từng mili-giây. SDP thử trên offer dạng Chrome.
- **Android:** `AndroidRtcPeer` biên dịch được (SDK 36); **chưa chạy trên máy thật** (máy dựng không có KVM để chạy emulator).
- **libwebrtc thật trên JVM (trong app Recam, `RealMediaTest`, webrtc-java 0.14.0):** cùng cách dựng như peer Android, video qua server Recam thật tới bên xem, cả đường trực tiếp lẫn ép qua TURN. Đổi bên gửi sang `addTransceiver` thì vẫn "Connected" nhưng không có khung hình: đã thử.
- **iOS:** `IosRtcPeer` và lớp Swift mẫu **chưa biên dịch** (cần Mac/Xcode).

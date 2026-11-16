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

## Đã kiểm / chưa kiểm

- **JVM, mỗi lần đổi code:** mọi hàng trong bảng trên, bằng peer giả theo luật của RTCPeerConnection thật và kênh báo hiệu giả có thể đảo thứ tự hay chậm;
  thời gian ảo nên các hạn 10–20 giây được đo đúng từng mili-giây. SDP thử trên offer dạng Chrome.
- **Chưa có:** peer thật cho Android/iOS (bước K3b), chưa thử với trình duyệt hay thiết bị thật.

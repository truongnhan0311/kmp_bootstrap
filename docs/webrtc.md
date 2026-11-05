# appkit-webrtc — WebRTC trung lập nền tảng

Interface WebRTC dùng chung (`RtcEngine`, `PeerConnection`, `Transceiver`, `DataChannel`, `LocalTrack`) cùng công cụ thuần Kotlin để xử lý SDP và ICE. Logic phiên viết một lần ở `commonMain`, test trên JVM bằng `FakeRtcEngine`. Kit **không** lo signaling: SDP và candidate đi qua REST, MQTT hay đường nào là việc của app.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-webrtc") }
```

| Nền tảng | `RtcEngine` |
|---|---|
| Android | `AndroidRtcEngine()` — libwebrtc `io.github.webrtc-sdk:android` 150.7871.04 (cùng bản flutter_webrtc 1.6.3). Gọi `AppKitAndroid.init(context)` trước. **Chưa biên dịch, chưa chạy trên thiết bị** |
| iOS | **Chưa có trong kit.** App tự cài `RtcEngine` (Swift với pod `WebRTC-SDK`, hoặc cinterop) |
| Test (JVM) | `FakeRtcEngine(encoders, decoders)` — sinh SDP có hình dạng thật, answer chỉ giữ codec máy mã hoá được |

| Công cụ | Làm gì |
|---|---|
| `SdpTools.preferVideoCodecs(sdp, listOf("H264", "VP8"))` | đưa codec lên đầu `m=video`, rtx đi kèm codec chính; `dropOthers = true` để xoá codec khác |
| `SdpTools.keepH264Profile(sdp, "42e01f", 1)` | chỉ giữ một profile H.264 |
| `SdpTools.hasDtlsFingerprint(sdp)` | có `a=fingerprint:sha-256` ở mức phiên hoặc ở mọi `m=`; không có thì đừng trả lời |
| `SdpTools.negotiatedVideoCodec(answer)` | codec hai bên đã chốt |
| `CandidateQueue(peer)` | giữ candidate đến trước `setRemoteDescription`, đưa vào đúng thứ tự khi sẵn sàng |
| `Transceiver.replaceTrack(track)` | gắn/gỡ track (ví dụ micro để nói chuyện) **không** đàm phán lại SDP |
| `RtcEngine.videoEncoders()` | codec máy mã hoá được. Android: phần mềm libwebrtc **không có H.264**, máy thiếu chip chỉ còn VP8 |

## Bên xem tạo offer

<!-- snippet: webrtc-offer -->
```kotlin
// Bên XEM tạo offer: nhận video, audio hai chiều (để bật nói chuyện sau bằng replaceTrack, không đàm phán lại),
// DataChannel "ctrl" cho lệnh. libwebrtc liệt kê VP8 trước H.264 nên ép lại thứ tự.
suspend fun viewerOffer(rtc: RtcEngine, ice: List<IceServer>): Pair<PeerConnection, SessionDescription> {
    val peer = rtc.createPeer(ice)
    peer.addTransceiver(MediaKind.Video, Direction.RecvOnly)
    peer.addTransceiver(MediaKind.Audio, Direction.SendRecv)    // chưa gắn micro: không gửi gì
    peer.createDataChannel("ctrl")
    val raw = peer.createOffer()
    val offer = raw.copy(sdp = SdpTools.preferVideoCodecs(raw.sdp, listOf("H264", "VP8")))
    peer.setLocalDescription(offer)
    return peer to offer                                        // gửi offer.sdp qua kênh signaling của app
}
```
<!-- /snippet -->

## Bên camera trả lời

<!-- snippet: webrtc-answer -->
```kotlin
// Bên CAMERA trả lời: từ chối SDP không có DTLS, nhận candidate đến sớm qua CandidateQueue.
suspend fun cameraAnswer(rtc: RtcEngine, ice: List<IceServer>, offer: SessionDescription, camera: LocalTrack): SessionDescription? {
    if (!SdpTools.hasDtlsFingerprint(offer.sdp)) return null     // không có a=fingerprint → không trả lời
    val peer = rtc.createPeer(ice)
    val candidates = CandidateQueue(peer)                         // signaling gọi candidates.add(c) cho mỗi candidate đến
    peer.addTransceiver(MediaKind.Video, Direction.SendOnly, camera)
    peer.setRemoteDescription(offer)
    candidates.remoteDescriptionSet()
    val answer = peer.createAnswer()
    peer.setLocalDescription(answer)
    return answer                                                 // SdpTools.negotiatedVideoCodec(answer.sdp): "H264" hoặc "VP8"
}
```
<!-- /snippet -->

## Biết trước codec gửi được

<!-- snippet: webrtc-codecs -->
```kotlin
val rtc: RtcEngine = FakeRtcEngine(encoders = listOf("VP8"))  // Android thật: AndroidRtcEngine()
val canSendH264 = "H264" in rtc.videoEncoders()                 // false: máy không có H.264 phần cứng → sẽ gửi VP8
```
<!-- /snippet -->

## Đã kiểm / chưa kiểm

- **Đã kiểm trên JVM:** `SdpTools` trên một offer rút gọn từ libwebrtc (VP8 trước, nhiều profile H.264, rtx, red, ulpfec); thương lượng codec giữa viewer và camera có/không có H.264; `CandidateQueue`; `replaceTrack` không đổi SDP; DataChannel giả.
- **Chưa kiểm:** `AndroidRtcEngine` (cần Android SDK để biên dịch, cần thiết bị để chạy); iOS chưa có bản cài.

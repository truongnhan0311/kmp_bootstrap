# appkit-mqtt — client MQTT tự giữ kết nối

Client MQTT 3.1.1 viết bằng Kotlin, chạy trên Android, iOS và desktop. Bật một lần rồi để đó: rớt mạng, broker khởi động lại, mật khẩu
hết hạn đều tự nối lại. Chỉ cần `appkit-core` và Ktor.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-mqtt") }
```

## Nối và nhận tin

<!-- snippet: mqtt-connect -->
```kotlin
val client = MqttClient(
    MqttOptions(
        clientId = "dev123", username = "dev123",
        password = { devicePassword() },                // gọi lại MỖI lần nối: mật khẩu có hạn vẫn đúng sau khi rớt mạng
        will = MqttWill("cam/presence/dev123", """{"online":false}""".encodeToByteArray()),   // broker phát khi rớt mạng
    ),
    connector,
)
client.start()                                          // tự nối lại: 1s, 2s, 4s… tối đa 30s
val listening = launch {
    client.subscribe("cam/device/in/dev123").collect { msg ->   // đang thu thì đang đăng ký; nối lại tự đăng ký lại
        println("lệnh: ${msg.text}")
    }
}
client.awaitConnected()
client.publish("cam/presence/dev123", """{"online":true}""", qos = 1, retain = true)   // treo tới khi broker xác nhận
```
<!-- /snippet -->

| Việc | Cách |
|---|---|
| Mật khẩu có hạn (mật khẩu thiết bị 5 phút, vé phiên dùng một lần) | `password` là hàm, gọi lại mỗi lần nối |
| Rớt mạng | nối lại sau 1s, 2s, 4s… tối đa 30s, lệch ngẫu nhiên ±20% để cả nghìn máy không nối cùng lúc |
| Nối lại xong | tự đăng ký lại mọi chủ đề đang có người nghe |
| `publish` QoS 1 | treo tới khi broker xác nhận; rớt giữa chừng thì gửi lại (cờ DUP) sau khi nối lại. Bên nhận phải chống trùng (`DuplicateFilter` của `appkit-iot`) |
| `publish` QoS 0 khi đang mất mạng | ném `MqttNotConnectedException` (không xếp hàng) |
| Nhiều nơi nghe cùng chủ đề | một lần SUBSCRIBE; người nghe cuối cùng dừng thì UNSUBSCRIBE |
| Broker im lặng | ping theo `keepAlive`; quá 1,5 lần không nghe gì thì coi như rớt (`MqttError.Kind.Timeout`) |
| Rớt mạng thật | broker phát Last Will (vd hiện diện "offline") |
| `stop()` | gửi DISCONNECT nên **không** có Last Will; huỷ các publish đang chờ |

## Trạng thái

<!-- snippet: mqtt-state -->
```kotlin
val state = client.state.value
val text = when (state) {
    MqttState.Connected -> "đã nối"
    is MqttState.Waiting -> if (state.lastError.badCredentials) "sai mật khẩu" else "nối lại sau ${state.retryIn}"
    is MqttState.Connecting, MqttState.Idle -> "đang nối"
    MqttState.Stopped -> "đã dừng"
}
client.stop()                                           // DISCONNECT: broker KHÔNG phát Last Will
```
<!-- /snippet -->

`MqttError.kind`: `Network`, `Refused` (broker từ chối, `code` 4 = sai tên/mật khẩu, 5 = không được phép), `Protocol`, `Timeout`.

## Đường truyền

| | Nền tảng | Khi nào |
|---|---|---|
| `WebSocketConnector("wss://…/mqtt")` | Android, iOS, desktop | **Mặc định cho app**: cổng 443 ít bị chặn, TLS do engine Ktor lo (OkHttp, Darwin, CIO). Gửi subprotocol `mqtt` |
| `TcpConnector(host, port)` | chỉ desktop (JVM) | test, công cụ, máy chủ. Không có TLS |

Muốn đặt proxy hay chứng chỉ riêng: truyền `HttpClient` của bạn vào `WebSocketConnector(url, client)` (phải cài plugin `WebSockets`).

## Giới hạn

- Chỉ QoS 0 và 1 (không có QoS 2). Không giữ phiên giữa các lần nối: `cleanSession = true` là cách dùng được kiểm.
- Tin QoS 1 đang chờ nằm trong bộ nhớ: tắt app là mất. Cần chắc chắn hơn thì app tự lưu và gửi lại.

## Đã kiểm / chưa kiểm

- **JVM, mỗi lần đổi code:** bộ mã hoá gói (byte theo chuẩn, độ dài biên 127/128/16383/16384, gói bị cắt hay gộp, dữ liệu hỏng),
  client với broker giả qua socket thật (sai mật khẩu, rớt mạng, gửi lại QoS 1, Last Will, keepalive, broker không trả CONNACK, huỷ đăng ký),
  WebSocket qua máy chủ Ktor thật.
- **Đã chạy tay với broker thật** (mochi-mqtt v2.7.9 trong server Recam), qua cả WebSocket và TCP: `RealBrokerTest` (bỏ qua nếu chưa đặt biến môi trường, xem đầu file).
- **Chưa kiểm:** chạy trên Android/iOS thật (engine OkHttp/Darwin), `wss://` qua TLS thật.

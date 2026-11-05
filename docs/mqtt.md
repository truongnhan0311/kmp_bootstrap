# appkit-mqtt — client MQTT 3.1.1

Kênh điều khiển thời gian thực (lệnh, trạng thái online, tin đẩy xuống thiết bị) bằng MQTT 3.1.1 **qua WebSocket** (`wss://…/mqtt`, subprotocol `mqtt`, cổng 443 nên ít bị tường lửa chặn). Một code chạy cho Android (OkHttp), iOS (Darwin) và desktop (CIO). Chỉ cần `appkit-core` và Ktor; không kéo `appkit-network`.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-mqtt") }
```

| Kit tự lo | Cách |
|---|---|
| CONNECT + **Last Will** | `MqttOptions.will`: broker phát khi client rớt mà không gửi DISCONNECT |
| Mật khẩu có hạn / có ký thời gian | `credentials` được hỏi lại ở **mỗi** lần nối |
| Đổi địa chỉ broker (chuyển shard) | `WebSocketConnector(…) { url }`: `url` được hỏi lại ở mỗi lần nối |
| QoS 1 | `publish` chờ PUBACK (`ackTimeoutMs`); tin QoS 1 nhận được tự gửi PUBACK |
| Giữ kết nối | PINGREQ mỗi `keepAliveSeconds`; không có PINGRESP thì coi như rớt và nối lại |
| Nối lại | chờ ngẫu nhiên đều trong `[0, min(maxDelaySeconds, 2^n)]` giây, về n = 0 sau `stableResetSeconds` giây ổn định |
| Đăng ký chủ đề | được nhớ, tự đăng ký lại sau mỗi lần nối lại |
| Ngắt êm | `stop()` gửi DISCONNECT: broker **không** phát Last Will |

Kit **không** tự gửi lại `publish` khi rớt mạng: lời gọi đang chờ PUBACK nhận `MqttException(ConnectionLost)`, app tự quyết có gửi lại không (tránh gửi trùng). QoS 2 không hỗ trợ. Kit không biết tên chủ đề hay định dạng tin của app.

## Tạo client

<!-- snippet: mqtt-setup -->
```kotlin
// Một client cho một danh tính (thiết bị hoặc người dùng). credentials và url được hỏi lại ở MỖI lần nối:
// mật khẩu có ký thời gian hay địa chỉ broker đổi (chuyển shard) đều tự đúng ở lần nối sau.
fun deviceClient(scope: CoroutineScope, devid: String, sign: () -> String, brokerUrl: () -> String) = MqttClient(
    MqttOptions(
        clientId = devid,
        keepAliveSeconds = 60,
        will = MqttWill("presence/$devid", """{"online":false}""".encodeToByteArray(), retain = true),  // broker phát khi rớt
        credentials = { MqttCredentials(username = devid, password = sign()) },
    ),
    WebSocketConnector(mqttHttpClient()) { brokerUrl() },     // wss://…/mqtt, đi cổng 443
    scope,
)
```
<!-- /snippet -->

## Dùng

<!-- snippet: mqtt-use -->
```kotlin
suspend fun serve(mqtt: MqttClient, devid: String) = coroutineScope {
    // NGHE TRƯỚC rồi mới nối: `messages` không phát lại, tin đến lúc chưa ai collect là mất.
    val first = async { mqtt.messages.first { it.topic == "in/$devid" } }  // tin QoS 1 nhận được đã tự PUBACK
    mqtt.start()                                               // nối + tự nối lại; theo dõi mqtt.state nếu cần hiện trạng thái
    mqtt.subscribe("in/$devid")                                // được nhớ: tự đăng ký lại sau mỗi lần nối lại
    mqtt.state.first { it == MqttState.Connected }
    mqtt.publish("presence/$devid", """{"online":true}""", retain = true)   // QoS 1: chờ PUBACK, lỗi thì ném MqttException
    mqtt.publish("out/$devid", "got ${first.await().payload.decodeToString()}")
    mqtt.stop()                                                // DISCONNECT êm: broker KHÔNG phát Last Will
}
```
<!-- /snippet -->

**Bẫy:** `messages` là `SharedFlow` không phát lại. Bắt đầu `collect` **trước** khi nối/đăng ký, nếu không tin đến sớm (kể cả tin retained) sẽ mất.

`state` cho biết `Idle` / `Connecting(attempt)` / `Connected` / `Waiting(attempt, delayMs, lastError)` / `Stopped`. `lastError` là tiếng Anh, không chứa mật khẩu. Nếu cần bỏ cuộc sau N lần (ví dụ để khởi động lại cả luồng), theo dõi `attempt` rồi gọi `stop()`.

## Đường truyền khác

`MqttConnector` là một hàm `open(): MqttConnection` (gửi byte, nhận byte, đóng). Cần TCP 8883 hay giả lập trong test thì tự cài; bộ giải mã `MqttFrameReader` đã lo gói bị cắt nhỏ hoặc dồn nhiều gói.

## Đã kiểm / chưa kiểm

- **Đã kiểm trên JVM:** bộ mã hoá so với byte tính tay theo spec, ranh giới remaining length, gói bị cắt/dồn; client với broker giả (Last Will, QoS 1, hết hạn chờ, từ chối đăng nhập, nối lại + đăng ký lại, mất PINGRESP, `stop`); một lần chạy qua **WebSocket thật** (localhost, server Ktor).
- **Chưa kiểm:** broker thật (mochi-mqtt, Mosquitto…), Android và iOS trên thiết bị.

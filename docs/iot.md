# appkit-iot — giao thức thiết bị kiểu Tuya

Phần **chung cho mọi app IoT**: thiết bị tự ký request, mật khẩu MQTT của thiết bị, khung thông điệp `header` + `msg`, mã ghép đôi dễ đọc,
data point có kiểu. Chỉ cần `appkit-core`, không dính mạng: app tự gửi bằng `appkit-network` / MQTT / DataChannel.

```kotlin
commonMain.dependencies { implementation("kmp.bootstrap:appkit-iot") }
```

Cái gì là **của app** thì để trong app: tên chủ đề MQTT, danh sách DP cụ thể, đường dẫn API, các loại thông điệp (`type`).

## Thiết bị ký request

Sau khi kích hoạt, thiết bị giữ `devid` + `seckey` và ký mọi request thay vì giữ JWT:

```
X-Sign = hex(HMAC-SHA256(seckey, METHOD + "\n" + PATH + "\n" + X-Time + "\n" + hex(SHA-256(body))))
X-Time = mili-giây Unix; lệch quá 5 phút bị từ chối (chống gửi lại)
Mật khẩu MQTT = ts + ":" + hex(HMAC-SHA256(seckey, devid + ":" + ts)), ts là GIÂY Unix, sống 300 giây
```

Khớp từng byte với server Go của Recam: test dùng vector sinh từ chính mã Go, và server khoá cùng vector đó.

<!-- snippet: iot-sign -->
```kotlin
// devid + seckey nhận khi kích hoạt; cất bằng AppStorage.secrets, KHÔNG để trong prefs.
val signer = DeviceSigner(seckey)
val headers = signer.headers("dev123", "PUT", "/v1/devices/dev123/info", body.encodeToByteArray())
// → X-Devid, X-Time, X-Sign: gắn vào request (đường dẫn không kèm query string)
val mqttPassword = MqttDeviceAuth.password(seckey, "dev123")   // tên đăng nhập MQTT là devid; tạo mới mỗi lần nối
```
<!-- /snippet -->

`DeviceSigner.verify` (và `MqttDeviceAuth.verify`) trả `SignCheck.Ok | BadSignature | ClockSkew | Malformed`, so sánh không lộ thời gian.

## Khung thông điệp

```json
{"header":{"v":1,"id":"m1","from":"api","to":"dev123","sessionid":"s1","type":"offer","ts":1763280000000},"msg":{...}}
```

Tên trường giống server Go (`sessionid`, `trace_id`, `security_level`); trường trống không ghi ra (như `omitempty`). `decode` kiểm phiên bản,
`from`/`to`/`type`/`ts`, giới hạn 64 KB, và **không ném lỗi** với dữ liệu lạ.

<!-- snippet: iot-envelope -->
```kotlin
val dedupe = DuplicateFilter()                                  // QoS 1 có thể giao một tin hai lần
when (val r = EnvelopeCodec.decode(received)) {                 // không bao giờ ném lỗi với dữ liệu lạ
    is EnvelopeResult.Ok -> if (dedupe.firstSeen(r.envelope.header.id)) handle(r.envelope)
    is EnvelopeResult.Invalid -> println("drop: ${r.reason}")  // lý do tiếng Anh, cho log
}
val reply = Envelope.of(type = "dp.report", from = "dev123", to = "api", msg = buildJsonObject { put("ok", true) })
val text = EnvelopeCodec.encode(reply)                          // gửi lên MQTT / DataChannel
```
<!-- /snippet -->

## Mã ghép đôi

8 ký tự từ bảng 32 ký tự bỏ `0 O 1 I`, hiện thành hai nhóm. Server sinh mã và giữ hạn dùng; app chỉ hiện và kiểm dạng.

<!-- snippet: iot-pairing -->
```kotlin
val shown = PairingCode.Default.format("K7Q2M9XA")               // "K7Q2 M9XA" để hiện to trên màn hình
val typed = PairingCode.Default.normalize(" k7q2-m9xa ")        // "K7Q2M9XA"; null nếu gõ sai dạng → báo ngay, khỏi gọi server
```
<!-- /snippet -->

## Data point

<!-- snippet: iot-dps -->
```kotlin
val schema = DpSchema(listOf(                                   // danh sách DP là của app
    DpSpec("101", "torch", DpType.Bool),
    DpSpec("103", "max_viewers", DpType.Integer(1, 10)),
    DpSpec("104", "battery", DpType.Integer(0, 100), writable = false),
))
val state = schema.validate(report)                             // báo cáo: DP lạ bị bỏ qua, sai kiểu là lỗi
val command = schema.command("torch" to true, "max_viewers" to 3)   // lệnh: kiểm kiểu, phạm vi, chỉ-đọc → {"101":true,"103":3}
```
<!-- /snippet -->

| Kiểu | Kiểm |
|---|---|
| `DpType.Bool` | `true`/`false` thật (chuỗi `"true"` bị từ chối) |
| `DpType.Integer(min, max, step)` | số nguyên trong khoảng, chia hết bước tính từ `min` |
| `DpType.Enum(values)` | chuỗi nằm trong danh sách |
| `DpType.Text(maxLength)` | chuỗi không dài quá |

Báo cáo từ thiết bị: DP lạ bị bỏ qua (thiết bị mới hơn app vẫn chạy). Lệnh gửi đi: DP lạ hoặc chỉ-đọc là lỗi.

## Đã kiểm / chưa kiểm

Toàn bộ là `commonMain`, test chạy trên JVM (`./gradlew -PdesktopOnly=true :appkit-iot:desktopTest`). Không có phần theo nền tảng.

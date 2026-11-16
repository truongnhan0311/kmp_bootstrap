package kmp.bootstrap.appkit.iot

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * VÍ DỤ CHẠY ĐƯỢC của appkit-iot. Các đoạn giữa `// region` và `// endregion` được `tools/sync-snippets.mjs` chép nguyên văn vào docs/iot.md.
 */
class IotExamples {
    @Test fun deviceRequest() = runBlocking {
        val seckey = "s3cr3t-key-0001"
        val body = """{"dps":{"103":3}}"""
        // region: iot-sign
        // devid + seckey nhận khi kích hoạt; cất bằng AppStorage.secrets, KHÔNG để trong prefs.
        val signer = DeviceSigner(seckey)
        val headers = signer.headers("dev123", "PUT", "/v1/devices/dev123/info", body.encodeToByteArray())
        // → X-Devid, X-Time, X-Sign: gắn vào request (đường dẫn không kèm query string)
        val mqttPassword = MqttDeviceAuth.password(seckey, "dev123")   // tên đăng nhập MQTT là devid; tạo mới mỗi lần nối
        // endregion
        assertEquals(setOf("X-Devid", "X-Time", "X-Sign"), headers.keys); assertTrue(mqttPassword.contains(':'))
    }

    @Test fun envelopes() {
        val received = """{"header":{"v":1,"id":"m1","from":"api","to":"dev123","type":"video.start","ts":1763280000000},"msg":{}}"""
        // region: iot-envelope
        val dedupe = DuplicateFilter()                                  // QoS 1 có thể giao một tin hai lần
        when (val r = EnvelopeCodec.decode(received)) {                 // không bao giờ ném lỗi với dữ liệu lạ
            is EnvelopeResult.Ok -> if (dedupe.firstSeen(r.envelope.header.id)) handle(r.envelope)
            is EnvelopeResult.Invalid -> println("drop: ${r.reason}")  // lý do tiếng Anh, cho log
        }
        val reply = Envelope.of(type = "dp.report", from = "dev123", to = "api", msg = buildJsonObject { put("ok", true) })
        val text = EnvelopeCodec.encode(reply)                          // gửi lên MQTT / DataChannel
        // endregion
        assertEquals(1, handled); assertTrue(text.startsWith("{\"header\":{\"v\":1,"))
    }

    private var handled = 0
    private fun handle(e: Envelope) { handled++; assertEquals("video.start", e.header.type) }

    @Test fun pairing() {
        // region: iot-pairing
        val shown = PairingCode.Default.format("K7Q2M9XA")               // "K7Q2 M9XA" để hiện to trên màn hình
        val typed = PairingCode.Default.normalize(" k7q2-m9xa ")        // "K7Q2M9XA"; null nếu gõ sai dạng → báo ngay, khỏi gọi server
        // endregion
        assertEquals("K7Q2 M9XA", shown); assertEquals("K7Q2M9XA", typed)
    }

    @Test fun dataPoints() {
        val report = buildJsonObject { put("101", true); put("103", 3); put("999", 1) }
        // region: iot-dps
        val schema = DpSchema(listOf(                                   // danh sách DP là của app
            DpSpec("101", "torch", DpType.Bool),
            DpSpec("103", "max_viewers", DpType.Integer(1, 10)),
            DpSpec("104", "battery", DpType.Integer(0, 100), writable = false),
        ))
        val state = schema.validate(report)                             // báo cáo: DP lạ bị bỏ qua, sai kiểu là lỗi
        val command = schema.command("torch" to true, "max_viewers" to 3)   // lệnh: kiểm kiểu, phạm vi, chỉ-đọc → {"101":true,"103":3}
        // endregion
        assertIs<DpResult.Ok>(state); assertEquals(buildJsonObject { put("101", true); put("103", 3) }, command)
    }
}

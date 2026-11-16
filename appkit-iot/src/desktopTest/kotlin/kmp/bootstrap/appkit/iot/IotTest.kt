package kmp.bootstrap.appkit.iot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vector GO_* sinh bằng mã Go của server Recam (`server/app/recam/crypto.go`: DeviceSignature, MQTTDevicePassword) và
 * `encoding/json` của Go. Server khoá cùng các giá trị này trong test của nó, nên hai bên không thể lệch nhau mà không ai biết.
 */
private const val SECKEY = "s3cr3t-key-0001"
private const val GO_SIG_PUT = "2a9709e29fa500feac705977e82f8363bb90f26ff82a6bf4c16fe2993caa6b70"
private const val GO_SIG_GET = "86b52c401128dcebd0db2aa8220ab7719e02ba61358dbcd7875d98a6ed2e7f94"
private const val GO_MQTT = "1763280000:f6320ae07e803cfd6f6037b6d425bb46d8f3c08321ff0fbddbbe52dac630be55"
private const val GO_ENVELOPE =
    """{"header":{"v":1,"id":"m1","from":"api","to":"dev123","sessionid":"s1","type":"offer","ts":1763280000000,"security_level":3},"msg":{"mode":"live","sdp":"v=0\r\na=fingerprint:sha-256 AB"}}"""
private const val GO_ENVELOPE_MINIMAL = """{"header":{"v":1,"from":"dev123","to":"api","type":"dp.report","ts":1763280000001},"msg":{"dps":{"103":3}}}"""

class DeviceAuthTest {
    private val t0 = 1_763_280_000_000L
    private val signer = DeviceSigner(SECKEY, nowMillis = { t0 })

    @Test fun signatureMatchesGoServer() = runTest {
        assertEquals(GO_SIG_PUT, signer.signature("put", "/v1/devices/dev123/info", t0, """{"dps":{"103":3}}""".encodeToByteArray()))
        assertEquals(GO_SIG_GET, signer.signature("GET", "/v1/url_config", t0))
    }

    @Test fun headersCarryDeviceTimeAndSignature() = runTest {
        val h = signer.headers("dev123", "GET", "/v1/url_config")
        assertEquals(mapOf("X-Devid" to "dev123", "X-Time" to t0.toString(), "X-Sign" to GO_SIG_GET), h)
    }

    @Test fun verifyAcceptsGoodRejectsTamperedSkewedAndMalformed() = runTest {
        val body = """{"dps":{"103":3}}""".encodeToByteArray()
        val path = "/v1/devices/dev123/info"
        assertEquals(SignCheck.Ok, signer.verify("PUT", path, t0.toString(), body, GO_SIG_PUT))
        assertEquals(SignCheck.Ok, signer.verify("PUT", path, t0.toString(), body, GO_SIG_PUT.uppercase()))
        assertEquals(SignCheck.BadSignature, signer.verify("PUT", path, t0.toString(), """{"dps":{"103":4}}""".encodeToByteArray(), GO_SIG_PUT))
        assertEquals(SignCheck.BadSignature, signer.verify("PUT", "/v1/devices/other/info", t0.toString(), body, GO_SIG_PUT))
        assertEquals(SignCheck.BadSignature, DeviceSigner("other-key", nowMillis = { t0 }).verify("PUT", path, t0.toString(), body, GO_SIG_PUT))
        val late = DeviceSigner(SECKEY, nowMillis = { t0 + 5 * 60_000 + 1 })
        assertEquals(SignCheck.ClockSkew, late.verify("PUT", path, t0.toString(), body, GO_SIG_PUT))
        val early = DeviceSigner(SECKEY, nowMillis = { t0 - 5 * 60_000 })
        assertEquals(SignCheck.Ok, early.verify("PUT", path, t0.toString(), body, GO_SIG_PUT), "đúng biên 5 phút vẫn nhận")
        assertEquals(SignCheck.Malformed, signer.verify("PUT", path, "abc", body, GO_SIG_PUT))
        assertEquals(SignCheck.Malformed, signer.verify("PUT", path, t0.toString(), body, "not-hex"))
    }

    @Test fun pathWithQueryIsRefused() = runTest {
        assertFailsWith<IllegalArgumentException> { signer.signature("GET", "/v1/x?a=1", t0) }
        assertFailsWith<IllegalArgumentException> { signer.signature("GET", "v1/x", t0) }
    }

    @Test fun mqttPasswordMatchesGoServer() = runTest {
        assertEquals(GO_MQTT, MqttDeviceAuth.password(SECKEY, "dev123", 1_763_280_000))
        assertEquals(SignCheck.Ok, MqttDeviceAuth.verify(SECKEY, "dev123", GO_MQTT, nowSeconds = 1_763_280_000 + 300))
        assertEquals(SignCheck.ClockSkew, MqttDeviceAuth.verify(SECKEY, "dev123", GO_MQTT, nowSeconds = 1_763_280_000 + 301))
        assertEquals(SignCheck.BadSignature, MqttDeviceAuth.verify(SECKEY, "dev124", GO_MQTT, nowSeconds = 1_763_280_000))
        assertEquals(SignCheck.Malformed, MqttDeviceAuth.verify(SECKEY, "dev123", "garbage", nowSeconds = 1_763_280_000))
    }
}

class EnvelopeTest {
    @Test fun readsWhatGoWritesAndWritesTheSameBytes() {
        val ok = assertIs<EnvelopeResult.Ok>(EnvelopeCodec.decode(GO_ENVELOPE))
        val h = ok.envelope.header
        assertEquals(EnvelopeHeader(1, "m1", "api", "dev123", "s1", "offer", null, 1_763_280_000_000, 3), h)
        assertEquals("live", ok.envelope.msg["mode"]!!.jsonPrimitive.content)
        assertEquals(GO_ENVELOPE, EnvelopeCodec.encode(ok.envelope), "cùng thứ tự trường, bỏ trường null như omitempty")
        val minimal = assertIs<EnvelopeResult.Ok>(EnvelopeCodec.decode(GO_ENVELOPE_MINIMAL))
        assertEquals(GO_ENVELOPE_MINIMAL, EnvelopeCodec.encode(minimal.envelope))
        assertEquals(3, minimal.envelope.msg["dps"]!!.jsonObject["103"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun rejectsBadMessagesWithoutThrowing() {
        fun reason(text: String) = assertIs<EnvelopeResult.Invalid>(EnvelopeCodec.decode(text)).reason
        assertTrue(reason("not json").startsWith("not an envelope"))
        assertTrue(reason("""{"header":{"v":2,"from":"a","to":"b","type":"x","ts":1}}""").contains("version"))
        assertTrue(reason("""{"header":{"v":1,"from":"","to":"b","type":"x","ts":1}}""").contains("from/to"))
        assertTrue(reason("""{"header":{"v":1,"from":"a","to":"b","type":" ","ts":1}}""").contains("type"))
        assertTrue(reason("""{"header":{"v":1,"from":"a","to":"b","type":"x","ts":0}}""").contains("ts"))
        assertTrue(reason("""{"msg":{}}""").startsWith("not an envelope"))
        assertTrue(EnvelopeCodec.decode("x".repeat(EnvelopeCodec.MAX_BYTES + 1)) is EnvelopeResult.Invalid)
        assertIs<EnvelopeResult.Ok>(EnvelopeCodec.decode("""{"header":{"v":2,"from":"a","to":"b","type":"x","ts":1},"extra":1}""", supportedVersions = setOf(1, 2)))
    }

    @Test fun duplicateFilterDropsRepeatsWithinTtlAndForgetsOldest() {
        var now = 0L
        val f = DuplicateFilter(capacity = 2, ttl = kotlin.time.Duration.parse("1s"), nowMillis = { now })
        assertTrue(f.firstSeen("a")); assertFalse(f.firstSeen("a"))
        assertTrue(f.firstSeen(null)); assertTrue(f.firstSeen(null), "không có id thì không lọc được")
        now = 1_001; assertTrue(f.firstSeen("a"), "hết hạn thì nhận lại")
        assertTrue(f.firstSeen("b")); assertTrue(f.firstSeen("c"))
        assertTrue(f.firstSeen("a"), "đầy thì quên id cũ nhất")
        assertFalse(f.firstSeen("c"))
    }
}

class PairingCodeTest {
    @Test fun generatesFormatsAndNormalizes() {
        val code = PairingCode.Default.generate()
        assertEquals(8, code.length); assertTrue(code.all { it in PairingCode.DEFAULT_ALPHABET })
        assertEquals("K7Q2 M9XA", PairingCode.Default.format("K7Q2M9XA"))
        assertEquals("K7Q2M9XA", PairingCode.Default.normalize(" k7q2-m9xa "))
        assertNull(PairingCode.Default.normalize("K7Q2M9X"), "thiếu ký tự")
        assertNull(PairingCode.Default.normalize("K7Q2M9X0"), "0 không có trong bảng (dễ nhầm với O)")
        assertNull(PairingCode.Default.normalize("K7Q2M9XAB"))
        assertFailsWith<IllegalArgumentException> { PairingCode(alphabet = "AAB") }
    }
}

class DataPointsTest {
    private val schema = DpSchema(listOf(
        DpSpec("101", "torch", DpType.Bool),
        DpSpec("102", "resolution_cap", DpType.Enum(listOf("480p", "720p", "1080p"))),
        DpSpec("103", "max_viewers", DpType.Integer(1, 10)),
        DpSpec("104", "battery", DpType.Integer(0, 100), writable = false),
        DpSpec("105", "name", DpType.Text(32)),
        DpSpec("106", "volume", DpType.Integer(0, 100, step = 5)),
    ))

    @Test fun reportKeepsValidAndIgnoresUnknown() {
        val dps = buildJsonObject { put("101", true); put("103", 3); put("104", 87); put("999", "future dp") }
        val ok = assertIs<DpResult.Ok>(schema.validate(dps))
        assertEquals(mapOf("101" to DpValue.Bool(true), "103" to DpValue.Integer(3), "104" to DpValue.Integer(87)), ok.values)
    }

    @Test fun wrongTypesAndRangesAreAllReported() {
        val dps = buildJsonObject {
            put("101", "true"); put("102", "4k"); put("103", 11); put("105", "x".repeat(33)); putJsonObject("104") {}
        }
        val bad = assertIs<DpResult.Invalid>(schema.validate(dps))
        assertEquals(listOf("101", "102", "103", "105", "104"), bad.errors.map { it.id })
        assertIs<DpResult.Invalid>(schema.validate(buildJsonObject { put("103", 2.5) }))
        assertIs<DpResult.Invalid>(schema.validate(buildJsonObject { put("106", 7) }), "7 không chia hết bước 5")
        assertIs<DpResult.Ok>(schema.validate(buildJsonObject { put("106", 35) }))
    }

    @Test fun commandsRejectUnknownAndReadOnly() {
        assertEquals(buildJsonObject { put("101", true); put("103", 4) }, schema.command("torch" to true, "103" to 4))
        assertFailsWith<IllegalArgumentException> { schema.command("battery" to 50) }
        assertFailsWith<IllegalArgumentException> { schema.command("nope" to 1) }
        assertFailsWith<IllegalArgumentException> { schema.command("max_viewers" to 0) }
        assertIs<DpResult.Invalid>(schema.validate(buildJsonObject { put("999", JsonPrimitive(1)) }, forWrite = true))
    }
}

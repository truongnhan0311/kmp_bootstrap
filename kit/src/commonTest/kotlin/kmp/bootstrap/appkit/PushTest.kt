package kmp.bootstrap.appkit

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kmp.bootstrap.appkit.push.DeviceInfo
import kmp.bootstrap.appkit.push.DevicesPushContract
import kmp.bootstrap.appkit.push.LegacyPushContract
import kmp.bootstrap.appkit.push.PushMessage
import kmp.bootstrap.appkit.push.PushManager
import kmp.bootstrap.appkit.push.PushTokenSource
import kmp.bootstrap.appkit.push.PushTokens
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushTest {
    private val device = { DeviceInfo("Pixel", "Pixel 8", "Android 15", "android") }
    @AfterTest fun reset() = PushTokens.set(null)

    private class Calls { val seen = mutableListOf<Triple<HttpMethod, String, String>>() }

    private fun manager(calls: Calls, prefs: KeyValueStore = MemoryKeyValueStore(), token: String? = "fcm1", status: HttpStatusCode = HttpStatusCode.OK, contract: kmp.bootstrap.appkit.push.PushContract = DevicesPushContract(Endpoints.taper())): PushManager {
        val engine = MockEngine { req -> calls.seen += Triple(req.method, req.url.encodedPath, req.body.toByteArray().decodeToString()); respond("{}", status) }
        val client = AuthedClient("https://x.test", MemoryTokenStore("a", "r"), PlainJsonContract(TEST_PLAIN), "app", engine, refreshMarginSeconds = 0)
        return PushManager(client, prefs, contract, appVersion = "1.0", tokenSource = PushTokenSource { token }, device = device)
    }

    @Test fun registersOnceAndNotAgainUntilSomethingChanges() = runTest {
        val calls = Calls(); val m = manager(calls)
        assertTrue(m.registerIfNeeded("u1")); assertTrue(m.registerIfNeeded("u1"))
        assertEquals(1, calls.seen.size, "mở app lần hai không gọi lại server")
        assertEquals(HttpMethod.Put, calls.seen[0].first); assertEquals("/api/v2/me/devices", calls.seen[0].second)
        assertTrue(calls.seen[0].third.contains("\"device_id\"") && calls.seen[0].third.contains("fcm1") && calls.seen[0].third.contains("android"))
        m.registerIfNeeded("u2")
        assertEquals(2, calls.seen.size, "đổi người dùng thì đăng ký lại")
        PushTokens.set("fcm2"); m.registerIfNeeded("u2")
        assertEquals(3, calls.seen.size, "token FCM đổi thì đăng ký lại")
    }

    @Test fun noTokenMeansNoCall() = runTest {
        val calls = Calls()
        assertFalse(manager(calls, token = null).registerIfNeeded())
        assertTrue(calls.seen.isEmpty())
    }

    @Test fun serverRejectionIsRetriedNextTime() = runTest {
        val calls = Calls(); val prefs = MemoryKeyValueStore()
        assertFalse(manager(calls, prefs, status = HttpStatusCode.InternalServerError).registerIfNeeded())
        assertNull(prefs.get("push.registered"), "chưa nhớ là đã đăng ký khi server từ chối")
        assertTrue(manager(calls, prefs).registerIfNeeded())
        assertEquals(2, calls.seen.size)
    }

    @Test fun deviceIdSurvivesAndUnregisterUsesIt() = runTest {
        val prefs = MemoryKeyValueStore(); val calls = Calls(); val m = manager(calls, prefs)
        val id = m.deviceId()
        assertEquals(32, id.length); assertEquals(id, manager(Calls(), prefs).deviceId(), "cùng máy cùng id")
        m.registerIfNeeded(); m.unregister()
        assertEquals(HttpMethod.Delete, calls.seen.last().first); assertEquals("/api/v2/me/devices/$id", calls.seen.last().second)
        assertNull(prefs.get("push.registered"))
        assertNotEquals(id, manager(Calls(), MemoryKeyValueStore()).deviceId(), "máy khác id khác")
    }

    @Test fun legacyContractMatchesDepotShapeAndHasNoUnregisterCall() = runTest {
        val calls = Calls(); val c = LegacyPushContract(Endpoints.qtDepot(), appKey = "qt-depot", appName = "QT Depot", buildNumber = "7")
        val m = manager(calls, contract = c)
        m.registerIfNeeded(); m.unregister()
        assertEquals(1, calls.seen.size, "depot gỡ qua /logout, không có endpoint riêng")
        assertEquals("/push", calls.seen[0].second)
        listOf("\"app\":\"qt-depot\"", "\"app_name\":\"QT Depot\"", "\"build_number\":\"7\"", "\"device_id\"").forEach { assertTrue(calls.seen[0].third.contains(it), it) }
    }

    @Test fun payloadParsingAcceptsUuidAndSilent() {
        val m = PushMessage.fromData(mapOf("type" to "email", "uuid" to "abc", "silent" to "1", "title" to "T"))
        assertEquals("email", m.type); assertEquals("abc", m.id); assertTrue(m.silent); assertEquals("T", m.title)
        assertEquals("x", PushMessage.fromData(mapOf("id" to "x", "uuid" to "y")).id, "id thắng uuid")
        assertNull(PushMessage.fromData(mapOf("type" to "")).type)
    }
}

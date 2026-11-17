package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.platform.DeviceKeyBackend
import kmp.bootstrap.appkit.platform.DeviceKeyLogin
import kmp.bootstrap.appkit.platform.DeviceKeyLogin.Outcome
import kmp.bootstrap.appkit.platform.DeviceKeyNotRegistered
import kmp.bootstrap.appkit.platform.DeviceKeyStore
import kmp.bootstrap.appkit.platform.FakeDeviceKeyStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Máy chủ giả làm đúng như Recam (PROTOCOL.md 3.5): một khoá cho mỗi (tài khoản, máy), challenge dùng một lần. */
private class FakeServer : DeviceKeyBackend<String> {
    val keys = mutableMapOf<String, String>()          // deviceId → public key
    val issued = mutableSetOf<String>()
    var userId = "7"
    var down = false
    private var n = 0
    override suspend fun register(deviceId: String, publicKey: String, name: String, platform: String) {
        if (down) error("network down"); keys[deviceId] = publicKey
    }
    override suspend fun challenge(userId: String, deviceId: String): String {
        if (down) error("network down")
        if (userId != this.userId || deviceId !in keys) throw DeviceKeyNotRegistered()
        return "ch-${++n}-$deviceId".also { issued += it }
    }
    override suspend fun login(challenge: String, signature: String): String {
        val device = challenge.substringAfter('-').substringAfter('-')
        if (device !in keys) throw DeviceKeyNotRegistered()
        check(issued.remove(challenge)) { "challenge used" }
        check(signature.endsWith(":$challenge")) { "bad signature" }
        return "session-for-$userId"
    }
    override suspend fun unregister(deviceId: String) { if (down) error("network down"); keys.remove(deviceId) }
}

class DeviceKeyLoginTest {
    private val prefs = MemoryKeyValueStore()
    private val store = FakeDeviceKeyStore()
    private val server = FakeServer()
    private fun login(s: DeviceKeyStore? = store) = DeviceKeyLogin(s, server, prefs, deviceId = { "install-1" })

    @Test fun enableThenLogin() = runTest {
        val l = login()
        assertNull(l.lastUser); assertEquals(Outcome.Unavailable, l.login("7"), "chưa bật")
        assertTrue(l.enable("7", "Pixel 7", "android"))
        assertEquals(store.keys[l.alias("7")], server.keys["install-1"], "máy chủ nhận đúng khoá công khai")
        assertEquals("7", l.lastUser)
        assertEquals(Outcome.Success("session-for-7"), l.login("7", reason = "Đăng nhập Recam"))
        assertEquals(listOf("Đăng nhập Recam"), store.prompts)
        assertTrue(server.issued.isEmpty(), "challenge đã dùng")
        assertEquals("7", login().lastUser, "nhớ qua lần mở app sau")
    }

    @Test fun cancelAndLockoutKeepItEnabled() = runTest {
        val l = login(); l.enable("7", "", "")
        store.next = DeviceKeyStore.ERROR_CANCELLED
        assertEquals(Outcome.Cancelled, l.login("7"))
        store.next = DeviceKeyStore.ERROR_LOCKED_OUT
        assertEquals(Outcome.LockedOut, l.login("7"))
        assertTrue(l.enabledFor("7"))
    }

    @Test fun invalidatedKeyDisables() = runTest {
        val l = login(); l.enable("7", "", "")
        store.next = DeviceKeyStore.ERROR_INVALIDATED            // người dùng thêm khuôn mặt / vân tay mới
        assertEquals(Outcome.Disabled, l.login("7"))
        assertFalse(l.enabledFor("7")); assertNull(l.lastUser); assertTrue(store.keys.isEmpty())
    }

    @Test fun serverRevokedDisables() = runTest {
        val l = login(); l.enable("7", "", "")
        server.keys.clear()                                       // thu hồi từ máy khác
        assertEquals(Outcome.Disabled, l.login("7"))
        assertFalse(l.enabledFor("7")); assertTrue(store.keys.isEmpty())
    }

    @Test fun networkErrorsAreRetryable() = runTest {
        val l = login(); l.enable("7", "", "")
        server.down = true
        assertTrue(l.login("7") is Outcome.Error)
        assertTrue(l.enabledFor("7"), "lỗi mạng không tắt tính năng")
        server.down = false
        assertTrue(l.login("7") is Outcome.Success)
    }

    @Test fun failedRegistrationLeavesNoKey() = runTest {
        val l = login(); server.down = true
        assertFailsWith<IllegalStateException> { l.enable("7", "", "") }
        assertTrue(store.keys.isEmpty()); assertFalse(l.enabledFor("7"))
    }

    @Test fun disableRemovesBothSides() = runTest {
        val l = login(); l.enable("7", "", "")
        l.disable("7")
        assertTrue(server.keys.isEmpty()); assertTrue(store.keys.isEmpty()); assertNull(l.lastUser)
        l.enable("7", "", ""); server.down = true
        l.disable("7")                                            // mạng hỏng: vẫn tắt trên máy
        assertFalse(l.enabledFor("7"))
    }

    @Test fun unavailableDevice() = runTest {
        assertFalse(login(null).available())
        assertFalse(login(null).enable("7", "", ""))
        val noAuth = FakeDeviceKeyStore(availableBiometric = false, availablePasscode = true)
        assertTrue(DeviceKeyLogin(noAuth, server, prefs, { "d" }, allowPasscode = true).available(), "chỉ có mật mã máy vẫn dùng được")
        assertFalse(DeviceKeyLogin(noAuth, server, prefs, { "d" }, allowPasscode = false).available())
    }

    @Test fun oneKeyPerAccount() = runTest {
        val l = login(); l.enable("7", "", "")
        server.userId = "8"; l.enable("8", "", "")
        assertEquals(2, store.keys.size); assertEquals("8", l.lastUser)
        l.forget("8")
        assertTrue(l.enabledFor("7")); assertNull(l.lastUser, "người bật gần nhất đã tắt")
    }
}

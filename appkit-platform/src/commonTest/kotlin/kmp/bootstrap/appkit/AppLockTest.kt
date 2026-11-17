package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.platform.AppLock
import kmp.bootstrap.appkit.platform.AppLock.State
import kmp.bootstrap.appkit.platform.DeviceAuthKind
import kmp.bootstrap.appkit.platform.DeviceAuthResult
import kmp.bootstrap.appkit.platform.FakeDeviceAuth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class AppLockTest {
    private var now = 0L
    private val prefs = MemoryKeyValueStore()
    private fun lock(auth: FakeDeviceAuth) = AppLock(auth, prefs, timeout = 5.minutes, nowMillis = { now })

    @Test fun offUntilEnabledAndEnablingNeedsOneSuccess() = runTest {
        val auth = FakeDeviceAuth(next = DeviceAuthResult.Cancelled)
        val l = lock(auth)
        assertEquals(State.Off, l.state.value)
        assertEquals(DeviceAuthResult.Cancelled, l.enable())
        assertFalse(l.enabled, "huỷ lúc bật thì không bật")
        auth.next = DeviceAuthResult.Success
        Language.current = "vi"
        try {
            assertEquals(DeviceAuthResult.Success, l.enable())
        } finally { Language.reset() }
        assertTrue(l.enabled); assertEquals(State.Unlocked, l.state.value)
        assertEquals(listOf("Unlock to continue", "Mở khoá để tiếp tục"), auth.prompts, "lời nhắc theo ngôn ngữ đang chọn")
    }

    @Test fun startsLockedAfterRestartAndUnlocks() = runTest {
        val auth = FakeDeviceAuth()
        lock(auth).enable()
        val afterRestart = lock(auth)                         // app mở lại: đọc lựa chọn đã lưu
        assertEquals(State.Locked, afterRestart.state.value)
        auth.next = DeviceAuthResult.Cancelled
        afterRestart.unlock()
        assertEquals(State.Locked, afterRestart.state.value, "huỷ thì vẫn khoá")
        auth.next = DeviceAuthResult.Success
        afterRestart.unlock()
        assertEquals(State.Unlocked, afterRestart.state.value)
    }

    @Test fun relocksOnlyAfterTimeoutInBackground() = runTest {
        val l = lock(FakeDeviceAuth())
        l.enable()
        l.onBackground(); now += 4.minutes.inWholeMilliseconds; l.onForeground()
        assertEquals(State.Unlocked, l.state.value, "4 phút: chưa khoá")
        l.onBackground(); now += 5.minutes.inWholeMilliseconds; l.onForeground()
        assertEquals(State.Locked, l.state.value, "5 phút: khoá lại")
        l.onForeground()
        assertEquals(State.Locked, l.state.value)
    }

    @Test fun deviceWithoutLockScreenFallsBackToPassword() = runTest {
        val auth = FakeDeviceAuth()
        lock(auth).enable()
        auth.kind = DeviceAuthKind.None                       // người dùng gỡ hết khoá màn hình
        val l = lock(auth)
        assertEquals(State.NeedsPassword, l.state.value)
        assertEquals(DeviceAuthResult.NotAvailable, l.unlock())
        l.passwordLoginSucceeded()
        assertEquals(State.Unlocked, l.state.value)
        assertEquals(DeviceAuthResult.NotAvailable, lock(FakeDeviceAuth(kind = DeviceAuthKind.None)).also { it.disable() }.enable())
    }

    @Test fun lockoutSendsToPasswordAndSignOutDisables() = runTest {
        val auth = FakeDeviceAuth()
        lock(auth).enable()
        val l = lock(auth)
        auth.next = DeviceAuthResult.LockedOut
        l.unlock()
        assertEquals(State.NeedsPassword, l.state.value)
        l.signedOut()
        assertFalse(l.enabled); assertEquals(State.Off, l.state.value)
        assertEquals(State.Off, lock(auth).state.value, "tắt được lưu lại")
    }
}

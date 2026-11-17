package kmp.bootstrap.appkit

import kmp.bootstrap.appkit.platform.AppLock
import kmp.bootstrap.appkit.platform.DeviceAuth
import kmp.bootstrap.appkit.platform.DeviceAuthKind
import kmp.bootstrap.appkit.platform.DeviceKeyBackend
import kmp.bootstrap.appkit.platform.DeviceKeyLogin
import kmp.bootstrap.appkit.platform.FakeDeviceKeyStore
import kmp.bootstrap.appkit.platform.FakeDeviceAuth
import kmp.bootstrap.appkit.platform.FakePlatformServices
import kmp.bootstrap.appkit.platform.PlatformServices
import kmp.bootstrap.appkit.platform.openLink
import kmp.bootstrap.appkit.platform.platformServices
import kmp.bootstrap.appkit.platform.shareOrCopy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** VÍ DỤ CHẠY ĐƯỢC của appkit-platform. Xem ghi chú ở CoreExamples. */
class PlatformExamples {
    // region: platform-screen
    // Màn hình chỉ biết interface — chạy trên Android, iOS, desktop hay bản giả đều như nhau.
    suspend fun shareAndAvatar(platform: PlatformServices, shareUrl: String): String? {
        platform.shareOrCopy("Xem buổi tập của mình: $shareUrl")     // mở hộp thoại chia sẻ; không có thì tự copy
        platform.openLink("https://example.com/help")               // chỉ mở http/https/mailto/tel — chuỗi lạ bị từ chối
        val image = platform.pickImage() ?: return null            // null nếu người dùng huỷ
        return "${image.filename} (${image.bytes.size} byte, ${image.contentType})"     // gửi image.bytes lên máy chủ bằng multipart
    }
    // endregion

    @Test fun withTheFake() = runBlocking {
        // region: platform-fake
        val fake = FakePlatformServices(timezone = "Asia/Ho_Chi_Minh", shareWorks = false)   // trong test: ghi lại lời gọi, không động tới hệ điều hành
        fake.nextImage = kmp.bootstrap.appkit.platform.PickedImage(byteArrayOf(1, 2, 3), "avatar.png", "image/png")
        val result = shareAndAvatar(fake, "https://app.example.com/s/abc")
        // endregion
        assertEquals("avatar.png (3 byte, image/png)", result); assertEquals(listOf("Xem buổi tập của mình: https://app.example.com/s/abc"), fake.copied)
        assertEquals(listOf("https://example.com/help"), fake.openedUrls)
    }

    @Test fun realDesktopServicesExposeTheTimezone() {
        // region: platform-real
        val platform = platformServices()          // Android: gọi AppKitAndroid.init(context) và AppKitPlatform.attach(this) ở MainActivity trước đó
        val zone = platform.timezoneId()           // "Asia/Ho_Chi_Minh" — dùng để chia ngày đúng múi giờ người dùng
        // endregion
        assertTrue(zone.isNotBlank())
    }

    @Test fun appLock() = runBlocking {
        val prefs = MemoryKeyValueStore()
        val auth: DeviceAuth = FakeDeviceAuth()                      // trong app: deviceAuth()
        // region: platform-applock
        val lock = AppLock(auth, prefs)                              // trong app: AppLock(deviceAuth(), storage.prefs)
        if (auth.available() != DeviceAuthKind.None) lock.enable()   // công tắc "Mở khoá bằng Face ID"; hỏi xác thực một lần
        val screen = when (lock.state.value) {
            AppLock.State.Off, AppLock.State.Unlocked -> "app"
            AppLock.State.Locked -> "unlock"                         // nút gọi lock.unlock()
            AppLock.State.NeedsPassword -> "login"                   // đăng nhập xong thì lock.passwordLoginSucceeded()
        }
        lock.onBackground()                                          // vòng đời app (ON_STOP / sceneDidEnterBackground)
        lock.onForeground()                                          // quá 5 phút thì tự về Locked
        // endregion
        assertEquals("app", screen)
    }

    // region: platform-devicekey-backend
    // Phía máy chủ Recam (PROTOCOL.md 3.5), viết bằng client HTTP của app. 401 device_key_not_registered → ném DeviceKeyNotRegistered.
    class RecamDeviceKeys(private val http: Map<String, (Map<String, String>) -> Map<String, String>>) : DeviceKeyBackend<String> {
        override suspend fun register(deviceId: String, publicKey: String, name: String, platform: String) {
            http.getValue("POST /v1/auth/device-keys")(mapOf("device_id" to deviceId, "public_key" to publicKey, "name" to name, "platform" to platform))
        }
        override suspend fun challenge(userId: String, deviceId: String) =
            http.getValue("POST /v1/auth/device-challenge")(mapOf("user_id" to userId, "device_id" to deviceId)).getValue("challenge")
        override suspend fun login(challenge: String, signature: String) =
            http.getValue("POST /v1/auth/device-login")(mapOf("challenge" to challenge, "signature" to signature)).getValue("refresh_token")
        override suspend fun unregister(deviceId: String) { http.getValue("DELETE /v1/auth/device-keys/{id}")(mapOf("device_id" to deviceId)) }
    }
    // endregion

    @Test fun deviceKeyLogin() = runBlocking {
        val keys = mutableMapOf<String, String>()
        val fakeHttp = mapOf<String, (Map<String, String>) -> Map<String, String>>(
            "POST /v1/auth/device-keys" to { b -> keys[b.getValue("device_id")] = b.getValue("public_key"); emptyMap() },
            "POST /v1/auth/device-challenge" to { b -> mapOf("challenge" to "ch-" + b.getValue("device_id")) },
            "POST /v1/auth/device-login" to { _ -> mapOf("refresh_token" to "r1") },
            "DELETE /v1/auth/device-keys/{id}" to { b -> keys.remove(b.getValue("device_id")); emptyMap() },
        )
        val store = FakeDeviceKeyStore()
        val prefs = MemoryKeyValueStore()
        val backend = RecamDeviceKeys(fakeHttp)
        val userId = "7"
        // region: platform-devicekey
        val login = DeviceKeyLogin(store, backend, prefs, deviceId = { "install-1" })   // trong app: deviceKeyStore(), storage.prefs, id lần cài
        if (login.available()) login.enable(userId, "iPhone của Minh", "ios")            // công tắc ở màn Tài khoản (đang đăng nhập)
        val who = login.lastUser                                                        // màn đăng nhập: có thì hiện "Đăng nhập bằng Face ID"
        val screen = when (val r = login.login(who ?: userId, reason = "Đăng nhập Recam")) {
            is DeviceKeyLogin.Outcome.Success -> "home"                                 // lưu r.session như sau /auth/login
            DeviceKeyLogin.Outcome.Cancelled -> "login"                                 // ở lại, người dùng bấm lại được
            DeviceKeyLogin.Outcome.Disabled, DeviceKeyLogin.Outcome.LockedOut,
            DeviceKeyLogin.Outcome.Unavailable -> "password"                            // về mật khẩu; Disabled: báo "bật lại trong Tài khoản"
            is DeviceKeyLogin.Outcome.Error -> "retry"                                  // lỗi mạng
        }
        // endregion
        assertEquals("home", screen)
        assertEquals("fake-pub-1", keys["install-1"])
        login.disable(userId)
        assertTrue(keys.isEmpty())
    }
}

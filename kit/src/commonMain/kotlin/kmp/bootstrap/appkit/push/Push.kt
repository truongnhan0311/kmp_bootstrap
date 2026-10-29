package kmp.bootstrap.appkit.push

import kmp.bootstrap.appkit.AppLog
import kmp.bootstrap.appkit.AuthedClient
import kmp.bootstrap.appkit.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random

/*
 * Thông báo đẩy (FCM). Phần dùng chung viết một lần ở đây; chỉ có hai thứ phụ thuộc nền tảng (lấy token, hiện thông báo)
 * và một thứ phụ thuộc backend (PushContract). Bài học từ qt-depot / Taper đã gói sẵn:
 *
 *  1. Khoá của máy là `deviceId` (id lần cài app, giữ qua đăng xuất), KHÔNG phải token FCM — token tự đổi được.
 *  2. Đăng ký sau khi đăng nhập VÀ mỗi khi token đổi; chỉ gọi server khi có gì mới (không gọi lặp mỗi lần mở app).
 *  3. Gỡ đăng ký TRƯỚC khi xoá token đăng nhập (sau đó không còn quyền gọi nữa).
 *  4. Server nên gửi payload CHỈ có `data` (không trường `notification` cấp cao nhất) — nếu không, Android ở nền tự hiện
 *     thông báo mà KHÔNG gọi onMessageReceived, app không chạy được code nào (kể cả tải trước dữ liệu).
 *  5. Mỗi thông báo một id riêng (theo id đối tượng) — dùng chung một id thì thông báo mới ĐÈ thông báo cũ, và PendingIntent
 *     trùng requestCode bị ghi đè extras khiến bấm thông báo A mở nhầm B.
 *  6. Nội dung thông báo không chứa bí mật (màn hình khoá + máy chủ Google/Apple đều thấy).
 */

/** Thông tin máy gửi lên server khi đăng ký. */
data class DeviceInfo(val name: String, val model: String, val os: String, val platform: String)
expect fun platformDeviceInfo(): DeviceInfo

/** Nguồn token theo nền tảng: Android = FirebaseMessaging; iOS = Swift ghi vào [PushTokens] (Firebase iOS chỉ có Swift). */
fun interface PushTokenSource { suspend fun token(): String? }
expect fun platformPushTokenSource(): PushTokenSource

/** Token hiện có, để nền tảng đẩy vào khi Firebase cấp token mới (Android `onNewToken`, iOS `didReceiveRegistrationToken`). */
object PushTokens {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()
    /** Gọi từ code nền tảng khi có token mới. */
    fun set(token: String?) { _token.value = token?.takeIf { it.isNotBlank() } }
}

/** Id cố định của LẦN CÀI app: tự sinh lần đầu, lưu bền, không xoá khi đăng xuất. */
class DeviceId(private val prefs: KeyValueStore, private val key: String = "push.device_id") {
    fun get(): String = prefs.get(key)?.takeIf { it.isNotBlank() } ?: run {
        val hex = "0123456789abcdef"
        buildString(32) { repeat(32) { append(hex[Random.nextInt(16)]) } }.also { prefs.put(key, it) }
    }
}

data class PushRegistration(val deviceId: String, val token: String, val device: DeviceInfo, val appVersion: String, val enabled: Boolean = true)

/** Một lời gọi HTTP mô tả bằng dữ liệu — để PushContract không phải biết AuthedClient. */
data class PushCall(val method: String, val path: String, val body: JsonObject?)

/** Chỗ DUY NHẤT hai backend khác nhau: đường dẫn và thân request đăng ký/gỡ. */
interface PushContract {
    fun register(r: PushRegistration): PushCall
    fun unregister(deviceId: String): PushCall
}

/**
 * Hợp đồng của Taper: `PUT /api/v2/me/devices {device_id, token, platform, device_name, app_version, enabled}`,
 * gỡ bằng `DELETE /api/v2/me/devices/{device_id}`. Khoá theo `(app, device_id)` ở server.
 */
class DevicesPushContract(private val base: String = "/api/v2/me/devices") : PushContract {
    override fun register(r: PushRegistration) = PushCall("PUT", base, buildJsonObject {
        put("device_id", JsonPrimitive(r.deviceId)); put("token", JsonPrimitive(r.token))
        put("platform", JsonPrimitive(r.device.platform)); put("device_name", JsonPrimitive(r.device.name))
        put("app_version", JsonPrimitive(r.appVersion)); put("enabled", JsonPrimitive(r.enabled))
    })
    override fun unregister(deviceId: String) = PushCall("DELETE", "$base/$deviceId", null)
}

/**
 * Hợp đồng kiểu qt-depot: `POST {path}` với `{token, device_name, device_model, os, version, platform, app_name, app_version,
 * build_number, app, device_id}`; không có endpoint gỡ riêng (server gỡ khi `/logout` nhận `device_id`) nên `unregister` là no-op.
 */
class LegacyPushContract(private val path: String = "/push", private val appKey: String, private val appName: String, private val buildNumber: String = "") : PushContract {
    override fun register(r: PushRegistration) = PushCall("POST", path, buildJsonObject {
        fun s(k: String, v: String) = put(k, JsonPrimitive(v))
        s("token", r.token); s("device_name", r.device.name); s("device_model", r.device.model); s("os", r.device.os); s("version", r.device.os)
        s("platform", r.device.platform); s("app_name", appName); s("app_version", r.appVersion); s("build_number", buildNumber); s("app", appKey); s("device_id", r.deviceId)
    })
    override fun unregister(deviceId: String) = PushCall("", "", null)
}

/**
 * Đăng ký thiết bị với server khi cần. Dùng:
 *     val push = PushManager(AppKit.client, AppKit.storage.prefs, DevicesPushContract(), appVersion = "1.0")
 *     push.registerIfNeeded()      // sau đăng nhập, và mỗi lần mở app (không gọi server nếu chưa có gì đổi)
 *     push.unregister()            // TRƯỚC khi đăng xuất
 */
class PushManager(
    private val client: AuthedClient,
    private val prefs: KeyValueStore,
    private val contract: PushContract,
    private val appVersion: String,
    private val tokenSource: PushTokenSource = platformPushTokenSource(),
    private val device: () -> DeviceInfo = ::platformDeviceInfo,
) {
    private val deviceId = DeviceId(prefs)

    /** Đăng ký nếu có token và token/người dùng khác lần đăng ký trước. Trả true nếu server đã nhận (hoặc không cần gửi lại). */
    suspend fun registerIfNeeded(userKey: String = ""): Boolean {
        val token = PushTokens.token.value ?: tokenSource.token()?.also { PushTokens.set(it) } ?: return false
        val fingerprint = "$userKey|$token|$appVersion"
        if (prefs.get(KEY_REGISTERED) == fingerprint) return true
        val call = contract.register(PushRegistration(deviceId.get(), token, device(), appVersion))
        val ok = send(call)
        if (ok) prefs.put(KEY_REGISTERED, fingerprint)
        return ok
    }

    /** Gỡ thiết bị khỏi thông báo — gọi TRƯỚC khi xoá token đăng nhập. Lỗi mạng không chặn đăng xuất. */
    suspend fun unregister() {
        prefs.put(KEY_REGISTERED, null)
        val call = contract.unregister(deviceId.get())
        if (call.method.isNotEmpty()) send(call)
    }

    fun deviceId(): String = deviceId.get()

    private suspend fun send(call: PushCall): Boolean = try {
        val r = client.raw(call.method, call.path, call.body)
        (r.status in 200..299).also { if (!it) AppLog.w("push", "server từ chối đăng ký thông báo (HTTP ${r.status})") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.w("push", "không đăng ký được thông báo", e); false
    }

    private companion object { const val KEY_REGISTERED = "push.registered" }
}

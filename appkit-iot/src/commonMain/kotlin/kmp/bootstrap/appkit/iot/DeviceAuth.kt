package kmp.bootstrap.appkit.iot

import kmp.bootstrap.appkit.AppCrypto
import kmp.bootstrap.appkit.Hex
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Xác thực của THIẾT BỊ với cloud, theo kiểu Tuya: sau khi kích hoạt, thiết bị giữ `devid` + `seckey` (bí mật riêng, cất bằng
 * AppStorage.secrets) và ký mọi request bằng HMAC-SHA256 thay vì giữ JWT.
 *
 *   Chữ ký request = hex(HMAC-SHA256(seckey, METHOD + "\n" + PATH + "\n" + X-Time + "\n" + hex(SHA-256(body))))
 *   X-Time         = mili-giây Unix dạng chuỗi; server từ chối nếu lệch quá maxSkew (mặc định 5 phút) → chống gửi lại.
 *   Mật khẩu MQTT  = ts + ":" + hex(HMAC-SHA256(seckey, devid + ":" + ts)), ts là GIÂY Unix; lệch tối đa 300 giây.
 *
 * Hai công thức này khớp từng byte với server Go của Recam (test dùng vector sinh từ chính mã Go).
 * PATH là đường dẫn KHÔNG kèm query string, đúng như server nhìn thấy. Body rỗng (GET) băm như mảng rỗng.
 */

/** Kết quả kiểm chữ ký. [Ok] là trường hợp duy nhất được chấp nhận. */
enum class SignCheck { Ok, BadSignature, ClockSkew, Malformed }

/** Tên header mang thông tin ký. Đổi nếu server dùng tên khác. */
data class SignHeaders(val device: String = "X-Devid", val time: String = "X-Time", val signature: String = "X-Sign")

class DeviceSigner(
    private val seckey: String,
    val names: SignHeaders = SignHeaders(),
    val maxSkew: Duration = 5.minutes,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init { require(seckey.isNotEmpty()) { "seckey must not be empty" } }

    /** Chữ ký hex chữ thường cho một request. [method] không phân biệt hoa thường. */
    suspend fun signature(method: String, path: String, timeMillis: Long, body: ByteArray = ByteArray(0)): String {
        require(path.startsWith("/") && '?' !in path) { "path must start with '/' and must not contain a query string" }
        val canonical = method.uppercase() + "\n" + path + "\n" + timeMillis + "\n" + Hex.encode(AppCrypto.sha256(body))
        return Hex.encode(AppCrypto.hmacSha256(seckey.encodeToByteArray(), canonical.encodeToByteArray()))
    }

    /** Ba header cần gắn vào request của thiết bị (`X-Devid`, `X-Time`, `X-Sign`), ký ở thời điểm hiện tại. */
    suspend fun headers(devid: String, method: String, path: String, body: ByteArray = ByteArray(0)): Map<String, String> {
        val t = nowMillis()
        return mapOf(names.device to devid, names.time to t.toString(), names.signature to signature(method, path, t, body))
    }

    /** Kiểm một request (phía server, hoặc test). Chữ ký hoa/thường đều nhận; so sánh không lộ thời gian. */
    suspend fun verify(method: String, path: String, timeHeader: String, body: ByteArray, sig: String): SignCheck {
        val t = timeHeader.toLongOrNull() ?: return SignCheck.Malformed
        if ((nowMillis() - t).let { if (it < 0) -it else it } > maxSkew.inWholeMilliseconds) return SignCheck.ClockSkew
        val got = runCatching { Hex.decode(sig) }.getOrNull() ?: return SignCheck.Malformed
        val want = Hex.decode(signature(method, path, t, body))
        return if (AppCrypto.constantTimeEquals(want, got)) SignCheck.Ok else SignCheck.BadSignature
    }
}

/** Mật khẩu MQTT của thiết bị: tên đăng nhập là `devid`, mật khẩu do hàm này sinh, sống [MqttDeviceAuth.MAX_SKEW]. */
object MqttDeviceAuth {
    val MAX_SKEW: Duration = 300.seconds

    suspend fun password(seckey: String, devid: String, epochSeconds: Long = Clock.System.now().epochSeconds): String {
        require(seckey.isNotEmpty() && devid.isNotEmpty()) { "seckey and devid must not be empty" }
        val mac = AppCrypto.hmacSha256(seckey.encodeToByteArray(), "$devid:$epochSeconds".encodeToByteArray())
        return "$epochSeconds:" + Hex.encode(mac)
    }

    suspend fun verify(
        seckey: String, devid: String, password: String,
        nowSeconds: Long = Clock.System.now().epochSeconds, maxSkew: Duration = MAX_SKEW,
    ): SignCheck {
        val ts = password.substringBefore(':', "").toLongOrNull() ?: return SignCheck.Malformed
        if ((nowSeconds - ts).let { if (it < 0) -it else it } > maxSkew.inWholeSeconds) return SignCheck.ClockSkew
        val want = password(seckey, devid, ts).encodeToByteArray()
        return if (AppCrypto.constantTimeEquals(want, password.encodeToByteArray())) SignCheck.Ok else SignCheck.BadSignature
    }
}

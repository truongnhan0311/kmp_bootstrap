package kmp.bootstrap.appkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class TokenPair(val access: String, val refresh: String?)

/**
 * Chỗ DUY NHẤT hai backend khác nhau: đường dẫn làm mới/đăng xuất, thân request, cách đọc token từ phản hồi, và thế nào
 * là "token bị từ chối". Phần cơ chế (single-flight, thử lại, báo hết phiên) nằm ở [AuthedClient] và dùng chung.
 */
interface AuthContract {
    /** Đường dẫn API — khai báo MỘT chỗ ở [Endpoints], contract chỉ đọc. */
    val endpoints: Endpoints
    val refreshPath: String get() = endpoints.refresh
    val logoutPath: String get() = endpoints.logout
    fun refreshBody(refreshToken: String): JsonObject
    fun logoutBody(refreshToken: String?, all: Boolean): JsonObject
    /** Đọc cặp token từ phản hồi đăng nhập/đăng ký/làm mới; null nếu không phải phản hồi thành công. */
    fun parseTokens(status: Int, body: String): TokenPair?
    /** Phản hồi này nói "access token hết hạn/bị thu hồi" (đáng thử làm mới rồi gửi lại)? */
    fun isTokenRejected(status: Int, body: String): Boolean
    /** Phản hồi của lời gọi làm mới nói refresh token đã chết thật (phải đăng nhập lại), khác lỗi tạm? */
    fun isRefreshDead(status: Int, body: String): Boolean
    /** Câu báo lỗi do SERVER viết trong phản hồi lỗi; null nếu không có (khi đó kit dùng câu mặc định theo ngôn ngữ). */
    fun errorMessage(status: Int, body: String): String?
    /** Đường đăng nhập/đăng ký/làm mới/đăng xuất không tự làm mới (tránh vòng lặp) — so khớp với [Endpoints], không đoán theo tên. */
    fun isAuthPath(path: String): Boolean = path.substringBefore('?') in endpoints.authPaths
}

private fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() } }

private fun parseObject(body: String): JsonObject? = try {
    kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject
} catch (e: Exception) { null }

/**
 * Kiểu của Taper: JSON phẳng `{access_token|token, refresh_token}`, lỗi là HTTP 401/4xx kèm `{message, code}`.
 * `code:"refresh_invalid"` hoặc 401 khi làm mới = phiên chết.
 */
class PlainJsonContract(override val endpoints: Endpoints) : AuthContract {
    override fun refreshBody(refreshToken: String) = buildJsonObject { put("refresh_token", refreshToken) }
    override fun logoutBody(refreshToken: String?, all: Boolean) = buildJsonObject { put("refresh_token", refreshToken ?: ""); put("all", all) }

    override fun parseTokens(status: Int, body: String): TokenPair? {
        if (status !in 200..299) return null
        val obj = parseObject(body) ?: return null
        val access = obj.str("access_token", "token") ?: return null
        return TokenPair(access, obj.str("refresh_token"))
    }

    override fun isTokenRejected(status: Int, body: String) = status == 401
    override fun isRefreshDead(status: Int, body: String) = status == 401
    override fun errorMessage(status: Int, body: String) = parseObject(body)?.str("message", "error")
}

/**
 * Kiểu khung bọc: khung `{code, message, data}` (HTTP thường vẫn 200), thành công khi `code == 200` và token nằm trong
 * `data`. Token hết hạn: `code == 401` hoặc thông điệp kiểu `Token_Revoked`.
 */
class EnvelopeContract(
    override val endpoints: Endpoints,
    /** Trường thêm vào thân làm mới/đăng xuất (ví dụ `app`, `platform`). */
    private val extra: Map<String, String> = emptyMap(),
) : AuthContract {
    override fun refreshBody(refreshToken: String) = buildJsonObject { put("refresh_token", refreshToken); extra.forEach { (k, v) -> put(k, v) } }
    override fun logoutBody(refreshToken: String?, all: Boolean) = buildJsonObject { put("refresh_token", refreshToken ?: ""); extra.forEach { (k, v) -> put(k, v) } }

    private fun code(obj: JsonObject?) = obj?.get("code")?.jsonPrimitive?.intOrNull

    override fun parseTokens(status: Int, body: String): TokenPair? {
        val obj = parseObject(body) ?: return null
        if (code(obj) != 200) return null
        val data = obj["data"] as? JsonObject ?: return null
        val access = data.str("token", "access_token") ?: return null
        return TokenPair(access, data.str("refresh_token"))
    }

    override fun isTokenRejected(status: Int, body: String): Boolean {
        if (status == 401) return true
        val obj = parseObject(body) ?: return false
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return isTokenRevokedMessage(message) || (code(obj) == 401 && message.contains("token", ignoreCase = true))
    }

    override fun isRefreshDead(status: Int, body: String): Boolean {
        if (status == 401) return true
        val obj = parseObject(body) ?: return false
        return code(obj) != null && code(obj) != 200 // server trả lời rõ ràng là từ chối (khác lỗi mạng/5xx)
            && status < 500
    }

    override fun errorMessage(status: Int, body: String) = (parseObject(body)?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** Thông điệp báo token bị thu hồi/hết hạn ("Token_Revoked", "token expired"…). */
fun isTokenRevokedMessage(message: String): Boolean {
    val m = message.trim().lowercase()
    if (m.isBlank()) return false
    return m == "token_revoked" || (m.contains("token") && (m.contains("revok") || m.contains("expired")))
}

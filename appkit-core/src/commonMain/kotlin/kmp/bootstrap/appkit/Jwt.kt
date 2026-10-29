package kmp.bootstrap.appkit

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object Jwt {
    /** Số giây còn lại của JWT (đọc `exp`, không kiểm chữ ký); null nếu không phải JWT. */
    @OptIn(ExperimentalEncodingApi::class)
    fun secondsLeft(jwt: String, nowEpochSeconds: Long): Long? {
        val parts = jwt.removePrefix("Bearer ").trim().split('.')
        if (parts.size != 3) return null
        return try {
            val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1])
            val exp = Json.parseToJsonElement(payload.decodeToString()).jsonObject["exp"]?.jsonPrimitive?.longOrNull ?: return null
            exp - nowEpochSeconds
        } catch (e: Exception) {
            null
        }
    }
}

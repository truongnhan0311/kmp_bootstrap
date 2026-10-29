package kmp.bootstrap.appkit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/*
 * Gọi API gọn, trả về ĐỐI TƯỢNG đã giải mã; mọi thất bại là một [ApiError] có `kind` (Network, Timeout, NotFound, Invalid, Server, Parse…):
 *
 *     val me: User = client.get("/api/me")
 *     val created: Item = client.post("/api/items", buildJsonObject { put("name", "x") })
 *     val maybe: Item? = client.getOrNull("/api/items/9")          // 404 → null, lỗi khác vẫn ném
 *     client.delete("/api/items/9")                                  // không cần thân trả về
 *
 * Gửi dữ liệu: truyền thẳng một object `@Serializable` làm body — tự đổi sang JSON, tự gắn JWT, tự làm mới phiên khi hết hạn:
 *
 *     val created: Item = client.post("/api/items", NewItem(name = "x"))          // POST   + body
 *     val updated: Item = client.put("/api/items/9", Item(9, "y"))                 // PUT    + body (thay cả)
 *     val patched: Item = client.patch("/api/items/9", Rename(name = "z"))         // PATCH  + body (sửa một phần)
 *     client.delete("/api/items/9")                                                // DELETE
 *     client.sendNoContent("POST", "/api/items/9/archive")                         // khi không cần dữ liệu trả về
 *
 * Phản hồi 2xx mà JSON sai định dạng → ApiError(kind = Parse) (không đổ app bằng lỗi giải mã thô).
 */

suspend inline fun <reified T> AuthedClient.get(path: String, auth: Boolean = true): T = request("GET", path, null, auth, serializer<T>())
suspend inline fun <reified T> AuthedClient.post(path: String, body: JsonObject? = null, auth: Boolean = true): T = request("POST", path, body, auth, serializer<T>())
suspend inline fun <reified T> AuthedClient.put(path: String, body: JsonObject? = null, auth: Boolean = true): T = request("PUT", path, body, auth, serializer<T>())
suspend inline fun <reified T> AuthedClient.patch(path: String, body: JsonObject? = null, auth: Boolean = true): T = request("PATCH", path, body, auth, serializer<T>())
suspend fun AuthedClient.delete(path: String, auth: Boolean = true) { requestUnit("DELETE", path, null, auth) }

// ---- body là object @Serializable (khỏi tự dựng JsonObject) ----

suspend inline fun <reified T, reified B> AuthedClient.post(path: String, body: B, auth: Boolean = true): T = request("POST", path, encodeBody(body, serializer<B>()), auth, serializer<T>())
suspend inline fun <reified T, reified B> AuthedClient.put(path: String, body: B, auth: Boolean = true): T = request("PUT", path, encodeBody(body, serializer<B>()), auth, serializer<T>())
suspend inline fun <reified T, reified B> AuthedClient.patch(path: String, body: B, auth: Boolean = true): T = request("PATCH", path, encodeBody(body, serializer<B>()), auth, serializer<T>())

/** Gửi mà không cần dữ liệu trả về (xoá, lưu trữ, bật/tắt…); 2xx là xong, còn lại ném [ApiError]. Body tuỳ chọn. */
suspend fun AuthedClient.sendNoContent(method: String, path: String, body: JsonObject? = null, auth: Boolean = true) { requestUnit(method, path, body, auth) }

fun <B> AuthedClient.encodeBody(body: B, serializer: KSerializer<B>): JsonObject {
    val element = try { json.encodeToJsonElement(serializer, body) } catch (e: Exception) { throw ApiError(0, "Không đổi được dữ liệu gửi đi sang JSON", null, ErrorKind.Other, e) }
    return element as? JsonObject ?: throw ApiError(0, "Body phải là một đối tượng JSON", null, ErrorKind.Other)
}

/** Như [get] nhưng 404 trả null — cho dữ liệu "có thể chưa có" (không phải sự cố). Lỗi khác vẫn ném. */
suspend inline fun <reified T> AuthedClient.getOrNull(path: String, auth: Boolean = true): T? =
    try { get<T>(path, auth) } catch (e: ApiError) { if (e.isNotFound) null else throw e }

/** Thực thi và giải mã; dùng bởi các hàm trên (public để inline gọi được). */
suspend fun <T> AuthedClient.request(method: String, path: String, body: JsonObject?, auth: Boolean, serializer: KSerializer<T>): T {
    val r = raw(method, path, body, auth)
    throwIfFailed(r)
    return try {
        json.decodeFromString(serializer, r.body)
    } catch (e: Exception) {
        throw ApiError(r.status, "Phản hồi của máy chủ không đúng định dạng", r.requestId, ErrorKind.Parse, e)
    }
}

suspend fun AuthedClient.requestUnit(method: String, path: String, body: JsonObject?, auth: Boolean) {
    throwIfFailed(raw(method, path, body, auth))
}

private fun AuthedClient.throwIfFailed(r: RawResponse) {
    if (r.status !in 200..299) throw ApiError(r.status, errorMessageOf(r), r.requestId)
}

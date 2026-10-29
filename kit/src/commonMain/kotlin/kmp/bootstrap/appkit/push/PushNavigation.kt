package kmp.bootstrap.appkit.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Một thông báo đã được bấm/nhận, theo data payload chuẩn: `type` (loại) + `id` (đối tượng), cùng `title`/`body`/`silent`. */
data class PushMessage(val type: String?, val id: String?, val title: String?, val body: String?, val silent: Boolean) {
    companion object {
        /** Đọc từ data payload (Android `RemoteMessage.data`, iOS `userInfo`). Khoá `uuid` được chấp nhận như `id` (qt-depot). */
        fun fromData(data: Map<String, String?>) = PushMessage(
            type = data["type"]?.takeIf { it.isNotBlank() },
            id = (data["id"] ?: data["uuid"])?.takeIf { it.isNotBlank() },
            title = data["title"], body = data["body"], silent = data["silent"] == "1",
        )
    }
}

/**
 * Cầu từ lúc bấm thông báo (nền tảng) tới điều hướng THẬT trong Compose. Nền tảng gọi [request]; màn gốc quan sát
 * [target], điều hướng xong gọi [consumed] để xoay màn hình/quay lại không điều hướng lặp. App tự quyết `type` → màn nào.
 */
object PushNavigation {
    private val _target = MutableStateFlow<PushMessage?>(null)
    val target: StateFlow<PushMessage?> = _target.asStateFlow()
    fun request(message: PushMessage) { _target.value = message }
    fun consumed() { _target.value = null }
}

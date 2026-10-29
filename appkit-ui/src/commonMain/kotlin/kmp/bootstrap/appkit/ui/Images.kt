package kmp.bootstrap.appkit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kmp.bootstrap.appkit.TtlCache
import kotlin.time.Duration.Companion.hours

/** Giải mã byte ảnh (PNG/JPEG/WebP) thành bitmap; null nếu hỏng. Cài theo nền tảng. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Bộ nhớ đệm ảnh đã giải mã theo URL (tối đa 100 ảnh, giữ 1 giờ) — cuộn danh sách không tải lại avatar. Đổi ảnh thì `ImageCache.remove(url)`. */
object ImageCache { val cache = TtlCache<String, ImageBitmap>(ttl = 1.hours, maxEntries = 100) }

/**
 * Ảnh đại diện tải từ mạng: hiện chữ cái đầu trong lúc chờ và khi tải hỏng. [fetch] là cách TẢI byte (kit không biết API nào):
 *
 *     AsyncAvatar(user.name, user.avatarUrl, fetch = { client.getBytes(it) })      // client là AuthedClient: tự gắn JWT
 */
@Composable
fun AsyncAvatar(name: String?, url: String?, fetch: suspend (String) -> ByteArray?, modifier: Modifier = Modifier, size: Int = 32) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = null
        if (url == null) return@LaunchedEffect
        image = runCatching { ImageCache.cache.getOrLoad(url) { decodeImage(fetch(url) ?: error("no image")) ?: error("bad image") } }.getOrNull()
    }
    Avatar(name, image, size, modifier)
}

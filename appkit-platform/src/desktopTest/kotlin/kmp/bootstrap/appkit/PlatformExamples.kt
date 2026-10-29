package kmp.bootstrap.appkit

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
}

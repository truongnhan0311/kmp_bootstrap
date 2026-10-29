package kmp.bootstrap.appkit.platform

/** Ảnh người dùng vừa chọn (byte gốc, tên tệp, kiểu nội dung) — gửi thẳng lên máy chủ bằng multipart. */
class PickedImage(val bytes: ByteArray, val filename: String, val contentType: String)

/*
 * Những việc CHỈ hệ điều hành làm được, qua MỘT interface cho mọi nền tảng — màn hình không bao giờ biết đang chạy trên gì:
 *
 *     val platform = platformServices()                // Android: cần AppKitPlatform.attach(activity) ở MainActivity
 *     platform.openUrl("https://example.com")           // mở trình duyệt
 *     platform.copyToClipboard("mã mời: ABC")
 *     platform.shareText("Xem buổi tập của mình: https://…")   // hộp thoại chia sẻ của hệ điều hành
 *     val image = platform.pickImage()                  // chọn ảnh từ thư viện; null nếu người dùng huỷ
 *     platform.timezoneId()                              // "Asia/Ho_Chi_Minh"
 *
 * Test và xem thử dùng [FakePlatformServices] (ghi lại lời gọi, không động tới hệ điều hành).
 */
interface PlatformServices {
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    /** Mở hộp thoại chia sẻ. Trả true nếu đã mở được; false thì app nên rơi về [copyToClipboard]. */
    fun shareText(text: String): Boolean
    /** Mở bộ chọn ảnh. Trả null nếu người dùng huỷ hoặc không chọn được. */
    suspend fun pickImage(): PickedImage?
    /** Múi giờ IANA của máy ("Asia/Ho_Chi_Minh"). */
    fun timezoneId(): String
}

/** Bản cài thật của nền tảng hiện tại. */
expect fun platformServices(): PlatformServices

/** Bản giả cho test và xem thử: ghi lại mọi lời gọi, trả giá trị bạn đặt. */
class FakePlatformServices(
    var timezone: String = "UTC",
    var shareWorks: Boolean = true,
    var nextImage: PickedImage? = null,
) : PlatformServices {
    val openedUrls = mutableListOf<String>()
    val copied = mutableListOf<String>()
    val shared = mutableListOf<String>()
    override fun openUrl(url: String) { openedUrls += url }
    override fun copyToClipboard(text: String) { copied += text }
    override fun shareText(text: String): Boolean { shared += text; return shareWorks }
    override suspend fun pickImage(): PickedImage? = nextImage.also { nextImage = null }
    override fun timezoneId(): String = timezone
}

/** Mở một liên kết an toàn: chỉ http/https/mailto/tel. Chuỗi lạ (javascript:, file:…) bị từ chối. Trả true nếu đã mở. */
fun PlatformServices.openLink(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme !in SAFE_SCHEMES) return false
    openUrl(url)
    return true
}

private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")

/** Chia sẻ, rơi về copy khi hệ điều hành không mở được hộp thoại. Trả true nếu đã chia sẻ, false nếu chỉ copy. */
fun PlatformServices.shareOrCopy(text: String): Boolean {
    if (shareText(text)) return true
    copyToClipboard(text)
    return false
}

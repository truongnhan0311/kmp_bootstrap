package kmp.bootstrap.appkit

import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Danh sách địa chỉ máy chủ theo thứ tự ưu tiên (tên chính, rồi dự phòng). Lỗi CHƯA TỚI được máy chủ (DNS, kết nối, SSL)
 * thì thử địa chỉ kế tiếp; thành công ở đâu nhớ ở đó, sau [retryPrimaryAfter] thử lại tên chính.
 * Một địa chỉ duy nhất = không làm gì.
 */
class Failover(private val source: () -> List<String>, private val retryPrimaryAfter: Duration = 15.minutes) {
    /** Danh sách cố định (trường hợp thường gặp). */
    constructor(baseUrls: List<String>, retryPrimaryAfter: Duration = 15.minutes) : this({ baseUrls }, retryPrimaryAfter)

    @Volatile private var active = 0
    @Volatile private var switchedAt: TimeMark? = null
    @Volatile private var known: List<String> = emptyList()

    /**
     * Danh sách địa chỉ HIỆN TẠI. Nguồn có thể đổi lúc chạy (app cho người dùng tự nhập địa chỉ máy chủ): đổi thì quên địa chỉ đang chạy
     * tốt và quay về địa chỉ đầu danh sách mới — phiên (token) không liên quan tới địa chỉ nên giữ nguyên.
     */
    val baseUrls: List<String> get() {
        val list = source()
        require(list.isNotEmpty()) { "at least one base URL is required" }
        if (list != known) { known = list; active = 0; switchedAt = null }

        return list
    }

    init { baseUrls }

    fun current(): String {
        val urls = baseUrls
        val mark = switchedAt
        if (active != 0 && mark != null && mark.elapsedNow() >= retryPrimaryAfter) { active = 0; switchedAt = null }
        return urls[active.coerceAtMost(urls.lastIndex)].trimEnd('/')
    }

    /** `url` tuyệt đối có nằm dưới một địa chỉ máy chủ của mình không? Dùng để KHÔNG gửi token tới URL của bên thứ ba. */
    fun owns(url: String): Boolean = baseUrls.any { val base = it.trimEnd('/'); url == base || url.startsWith("$base/") }

    fun markWorking(url: String) {
        val i = baseUrls.indexOfFirst { it.trimEnd('/') == url }
        if (i < 0 || i == active) return
        active = i
        switchedAt = if (i == 0) null else TimeSource.Monotonic.markNow()
    }

    fun next(after: String): String {
        val i = baseUrls.indexOfFirst { it.trimEnd('/') == after }
        return baseUrls[(i + 1).mod(baseUrls.size)].trimEnd('/')
    }
}

/** Lỗi xảy ra TRƯỚC khi request tới server — gửi lại sang địa chỉ khác an toàn với mọi method. */
internal fun isUnreachableError(error: Throwable): Boolean {
    var e: Throwable? = error
    var depth = 0
    while (e != null && depth < 5) {
        val name = e::class.simpleName.orEmpty()
        val message = e.message.orEmpty()
        if (UNREACHABLE_CLASSES.any { name.contains(it) }) return true
        if (UNREACHABLE_MESSAGES.any { message.contains(it, ignoreCase = true) }) return true
        e = e.cause; depth++
    }
    return false
}

internal fun isTimeoutError(error: Throwable): Boolean =
    error::class.simpleName.orEmpty().contains("Timeout") || error.message.orEmpty().contains("timed out", ignoreCase = true)

private val UNREACHABLE_CLASSES = listOf("UnknownHost", "ConnectException", "NoRouteToHost", "ConnectTimeout", "SSLException", "SSLHandshake", "SSLPeerUnverified", "UnresolvedAddress")
private val UNREACHABLE_MESSAGES = listOf("Unable to resolve host", "No address associated with hostname", "Failed to connect", "Code=-1003", "Code=-1004", "Code=-1006", "Code=-1200", "Code=-1202", "hostname could not be found", "Could not connect to the server")

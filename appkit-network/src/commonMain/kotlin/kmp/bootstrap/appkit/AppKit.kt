package kmp.bootstrap.appkit

import io.ktor.client.engine.HttpClientEngine

/**
 * Cửa vào toàn cục, để gọi từ BẤT KỲ ĐÂU trong app mà không phải truyền đối tượng qua từng màn:
 *
 *     // một lần, lúc khởi động
 *     AppKit.start(AppKitConfig(name = "my-app", baseUrls = listOf("https://api.example.com"), contract = PlainJsonContract(Endpoints.taper())))
 *
 *     // ở đâu cũng được
 *     AppKit.client.send("GET", "/api/me") { … }
 *     AppKit.refreshSession()          // làm mới phiên ngay
 *     AppKit.storage.prefs.putBool("dark", true)
 *     AppLog.i("home", "opened")
 *
 * Test hoặc app muốn tự quản lý thì bỏ qua `AppKit`, dùng thẳng `AuthedClient` / `AppStorage`.
 */
class AppKitConfig(
    val name: String,
    val baseUrls: List<String>,
    val contract: AuthContract,
    val appId: String = name,
    val engine: HttpClientEngine? = null,
    val logToFile: Boolean = true,
    /** Địa chỉ máy chủ lấy lúc chạy (app cho người dùng tự đổi máy chủ). Có thì thay cho [baseUrls]; phiên đăng nhập giữ nguyên khi địa chỉ đổi. */
    val baseUrlsProvider: (() -> List<String>)? = null,
    /** Làm mới access token SỚM khi còn ít hơn chừng này giây (đọc `exp` của JWT); 0 = chỉ làm mới sau khi bị từ chối. */
    val refreshMarginSeconds: Long = 120,
    /** Số lần tự thử lại request ĐỌC (GET) khi gặp timeout/5xx/429; 0 = tắt. Request ghi không bao giờ tự thử lại. */
    val getRetries: Int = 1,
    /** Thời gian chờ tối đa cho một request (ms). Tăng cho app có lời gọi chậm (ví dụ AI trả lời hàng chục giây). */
    val requestTimeoutMs: Long = 30_000,
)

object AppKit {
    private var _client: AuthedClient? = null
    private var _storage: AppStorage? = null
    private var _fileLog: RotatingFileSink? = null
    private var _endpoints: Endpoints? = null

    val client: AuthedClient get() = _client ?: error("Call AppKit.start(...) first")
    /** Đường dẫn API của app — lấy từ đây thay vì gõ chuỗi ở từng nơi. */
    val endpoints: Endpoints get() = _endpoints ?: error("Call AppKit.start(...) first")
    val storage: AppStorage get() = _storage ?: error("Call AppKit.start(...) first")

    fun start(config: AppKitConfig) {
        val storage = AppStorage(config.name)
        _storage = storage
        _endpoints = config.contract.endpoints
        val sinks = mutableListOf<LogSink>(ConsoleSink())
        if (config.logToFile) runCatching { RotatingFileSink(platformLogDir(config.name)).also { _fileLog = it; sinks += it } }
        AppLog.install(*sinks.toTypedArray())
        val failover = config.baseUrlsProvider?.let { Failover(it) } ?: Failover(config.baseUrls)
        _client = AuthedClient(
            HttpTransport(failover, config.appId, config.engine, config.getRetries, requestTimeoutMs = config.requestTimeoutMs),
            storage.tokens, config.contract, config.refreshMarginSeconds,
        )
    }

    /** Làm mới phiên ngay — xem [AuthedClient.refreshNow]. */
    suspend fun refreshSession(): Boolean = client.refreshNow()

    /** Gọi khi app sắp thoát để dòng log cuối kịp xuống đĩa. */
    suspend fun flushLogs() { _fileLog?.flush() }
}

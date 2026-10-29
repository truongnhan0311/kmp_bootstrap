package kmp.bootstrap.appkit

import io.ktor.client.engine.HttpClientEngine

/**
 * Cửa vào toàn cục, để gọi từ BẤT KỲ ĐÂU trong app mà không phải truyền đối tượng qua từng màn:
 *
 *     // một lần, lúc khởi động
 *     AppKit.start(AppKitConfig(name = "my-app", baseUrls = listOf("https://api.example.com"), contract = PlainJsonContract()))
 *
 *     // ở đâu cũng được
 *     AppKit.client.send("GET", "/api/me") { … }
 *     AppKit.refreshSession()          // làm mới phiên ngay
 *     AppKit.storage.prefs.putBool("dark", true)
 *     AppLog.i("home", "đã mở")
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
)

object AppKit {
    private var _client: AuthedClient? = null
    private var _storage: AppStorage? = null
    private var _fileLog: RotatingFileSink? = null

    val client: AuthedClient get() = _client ?: error("Call AppKit.start(...) first")
    val storage: AppStorage get() = _storage ?: error("Call AppKit.start(...) first")

    fun start(config: AppKitConfig) {
        val storage = AppStorage(config.name)
        _storage = storage
        val sinks = mutableListOf<LogSink>(ConsoleSink())
        if (config.logToFile) runCatching { RotatingFileSink(platformLogDir(config.name)).also { _fileLog = it; sinks += it } }
        AppLog.install(*sinks.toTypedArray())
        _client = AuthedClient(Failover(config.baseUrls), storage.tokens, config.contract, config.appId, config.engine)
    }

    /** Làm mới phiên ngay — xem [AuthedClient.refreshNow]. */
    suspend fun refreshSession(): Boolean = client.refreshNow()

    /** Gọi khi app sắp thoát để dòng log cuối kịp xuống đĩa. */
    suspend fun flushLogs() { _fileLog?.flush() }
}

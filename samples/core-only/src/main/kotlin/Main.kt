import kmp.bootstrap.appkit.*
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours

fun main() = runBlocking {
    Language.current = "vi"
    val box = AppCrypto.encryptText("xin chào", "mật khẩu")
    println("giải mã: " + AppCrypto.decryptText(box, "mật khẩu"))
    val cache = TtlCache<String, String>(1.hours)
    println("cache: " + cache.getOrLoad("k") { "tải 1 lần" } + " / " + cache.getOrLoad("k") { "không chạy" })
    println("lỗi mẫu: " + Language.text(Msg.NetworkUnreachable))
}

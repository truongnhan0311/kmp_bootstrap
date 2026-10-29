package kmp.bootstrap.appkit

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/**
 * VÍ DỤ CHẠY ĐƯỢC của appkit-core. Các đoạn giữa `// region` và `// endregion` được `tools/sync-snippets.mjs` chép nguyên văn vào docs/core.md,
 * nên tài liệu không bao giờ lệch khỏi code đã biên dịch và đã chạy.
 */
@Serializable data class UiSettings(val theme: String, val fontSize: Int)

class CoreExamples {
    @Test fun language() {
        // region: core-language
        Language.current = "vi"                              // app đặt; bỏ dòng này thì theo ngôn ngữ hệ thống, không có thì "en"
        val vi = Language.text(Msg.NetworkUnreachable)       // "Không kết nối được máy chủ. …"
        Language.register("fr", mapOf(Msg.Timeout to "Délai dépassé"))   // thêm ngôn ngữ / ghi đè một câu
        Language.current = "fr"
        val fr = Language.text(Msg.Timeout)                  // "Délai dépassé"
        val fallback = Language.text(Msg.NotFound)           // fr chưa có câu này → tiếng Anh
        // endregion
        assertEquals("Délai dépassé", fr); assertEquals("Not found.", fallback); assertEquals(true, vi.startsWith("Không")); Language.reset()
    }

    @Test fun storage() {
        System.setProperty("appkit.storage.dir", Files.createTempDirectory("ex").toString())
        // region: core-storage
        val storage = AppStorage("my-app")                   // Android: gọi AppKitAndroid.init(context) một lần trước đó
        storage.prefs.putBool("dark", true)                  // lựa chọn thường (giao diện, ngôn ngữ…)
        storage.prefs.putInt("launches", 3)
        storage.prefs.putJson("settings", UiSettings.serializer(), UiSettings("dark", 14))
        val settings = storage.prefs.getJson("settings", UiSettings.serializer())   // null nếu chưa có hoặc dữ liệu cũ hỏng
        storage.tokens.save(access = "jwt", refresh = "refresh")                  // token nằm ở nơi mã hoá của máy (Keystore/Keychain)
        // endregion
        assertEquals(UiSettings("dark", 14), settings); assertEquals("jwt", AppStorage("my-app").tokens.access()); System.clearProperty("appkit.storage.dir")
    }

    @Test fun crypto() = runBlocking {
        // region: core-crypto
        val box = AppCrypto.encryptText("nội dung bí mật", password = "mật khẩu")    // chuỗi base64, lưu/gửi được
        val text = AppCrypto.decryptText(box, password = "mật khẩu")                 // sai mật khẩu hoặc bị sửa → ném CryptoError
        val key = AppCrypto.generateKey()                                            // hoặc dùng khoá 32 byte thô
        val bytes = AppCrypto.decrypt(AppCrypto.encrypt("abc".encodeToByteArray(), key), key)
        // endregion
        assertEquals("nội dung bí mật", text); assertEquals("abc", bytes.decodeToString())
    }

    @Test fun ttlCache() = runBlocking {
        // region: core-ttlcache
        val profiles = TtlCache<String, String>(ttl = 1.hours)
        var loads = 0
        val a = profiles.getOrLoad("user-1") { loads++; "Nhan" }     // chạy loader
        val b = profiles.getOrLoad("user-1") { loads++; "không chạy" } // còn hạn → không gọi lại
        profiles.remove("user-1")                                     // xoá tay; clear() xoá hết
        // endregion
        assertEquals(1, loads); assertEquals("Nhan", a); assertEquals("Nhan", b)
    }

    @Test fun logAndRedaction() = runBlocking {
        val dir = okio.fakefilesystem.FakeFileSystem()
        // region: core-log
        val file = RotatingFileSink(platformLogDir("my-app"), maxBytes = 1_000_000, keepFiles = 5)   // xoay vòng, giữ 15 ngày
        AppLog.install(ConsoleSink(), file)
        AppLog.i("auth", "signed in")                                  // dòng log luôn tiếng Anh, dễ grep
        AppLog.w("sync", "slow response")
        val safe = Redactor.redact("password=hunter2 token=eyJhbGciOiJIUzI1NiJ9.payload.SIGNATURE12345")
        // → password=[redacted] token=[redacted…12345]   (mật khẩu che hẳn, token giữ 5 ký tự cuối để tra)
        // endregion
        assertEquals("password=[redacted] token=[redacted…12345]", safe); AppLog.install(); dir.checkNoOpenFiles()
    }

    @Test fun timers() = runBlocking {
        val seen = mutableListOf<String>()
        // region: core-timers
        val timers = AppTimers()
        timers.after(50.milliseconds) { seen += "một lần" }                         // chạy một lần sau 50 ms
        val poll = timers.every(40.milliseconds, immediately = true) { seen += "lặp" } // lặp, không bao giờ chồng lên nhau
        val search = Debouncer(timers.scope, 60.milliseconds)
        search.submit { seen += "a" }; search.submit { seen += "ab" }                // chỉ lần cuối chạy, sau khi im 60 ms
        kotlinx.coroutines.delay(200)
        poll.cancel(); timers.cancelAll()
        // endregion
        assertEquals(true, "một lần" in seen && "ab" in seen && "a" !in seen && seen.count { it == "lặp" } >= 2)
    }
}

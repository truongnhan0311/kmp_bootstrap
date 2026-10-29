package vn.truongnhan.appkit

import kotlinx.serialization.Serializable
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class Settings(val lang: String, val size: Int = 1)

class StorageTest {
    private lateinit var dir: java.io.File

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("appkit").toFile(); System.setProperty("appkit.storage.dir", dir.path) }
    @AfterTest fun tearDown() { System.clearProperty("appkit.storage.dir"); dir.deleteRecursively() }

    @Test fun valuesSurviveReopeningTheApp() {
        AppStorage("demo").prefs.put("lang", "vi")
        assertEquals("vi", AppStorage("demo").prefs.get("lang"), "mở lại app vẫn còn")
    }

    @Test fun appsDoNotSeeEachOthersData() {
        AppStorage("a").prefs.put("k", "1")
        assertNull(AppStorage("b").prefs.get("k"))
    }

    @Test fun putNullRemoves() {
        val s = AppStorage("demo").prefs
        s.put("k", "v"); s.put("k", null)
        assertNull(AppStorage("demo").prefs.get("k"))
    }

    @Test fun typedHelpers() {
        val s = AppStorage("demo").prefs
        assertEquals(false, s.getBool("dark")); s.putBool("dark", true); assertEquals(true, s.getBool("dark"))
        assertEquals(7, s.getInt("n", 7)); s.putInt("n", 3); assertEquals(3, s.getInt("n"))
        assertNull(s.getJson("cfg", Settings.serializer()))
        s.putJson("cfg", Settings.serializer(), Settings("en", 2))
        assertEquals(Settings("en", 2), s.getJson("cfg", Settings.serializer()))
        s.put("cfg", "{hỏng"); assertNull(s.getJson("cfg", Settings.serializer()), "dữ liệu cũ hỏng không làm app đổ")
    }

    @Test fun tokensPersistAndClear() {
        AppStorage("demo").tokens.save("acc", "ref")
        val again = AppStorage("demo").tokens
        assertEquals("acc", again.access()); assertEquals("ref", again.refresh())
        again.save("acc2", null); assertEquals("ref", again.refresh(), "refresh null = giữ cũ")
        again.clear(); assertNull(AppStorage("demo").tokens.access())
    }

    @Test fun secretsFileIsOwnerOnlyAndPrefsAreSeparate() {
        val s = AppStorage("demo")
        s.tokens.save("acc", "ref")
        val secretFile = dir.resolve("demo.secrets")
        assertTrue(secretFile.exists())
        if (!System.getProperty("os.name").lowercase().contains("win"))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(secretFile.toPath())))
        assertNull(s.prefs.get("auth.access"), "token không lẫn vào prefs thường")
    }

    @Test fun serverUrlIsNormalisedAndRemembered() {
        val u = StoredServerUrl(AppStorage("demo").prefs, "https://default")
        assertEquals("https://default", u.get())
        u.set("  api.example.com/  "); assertEquals("https://api.example.com", StoredServerUrl(AppStorage("demo").prefs, "x").get())
        u.set(""); assertEquals("https://default", u.get())
    }
}

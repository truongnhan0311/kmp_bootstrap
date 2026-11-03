package kmp.bootstrap.appkit

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {
    @Serializable private data class Prefs(val a: Int, val b: String = "x")

    @Test fun memoryKeyValueStoreRemovesOnNull() {
        val s = MemoryKeyValueStore()
        s.put("k", "v"); assertEquals("v", s.get("k"))
        s.put("k", null); assertNull(s.get("k"))
        assertNull(s.get("never"))
    }

    @Test fun savingWithoutARefreshTokenKeepsTheOldOne() {
        // Server không xoay vòng refresh token: làm mới chỉ trả access mới, refresh cũ vẫn phải còn.
        val t = MemoryTokenStore("a1", "r1")
        t.save("a2", null)
        assertEquals("a2", t.access()); assertEquals("r1", t.refresh())
        t.save("a3", "r3")
        assertEquals("r3", t.refresh())
    }

    @Test fun clearRemovesBothTokens() {
        val t = MemoryTokenStore("a", "r"); t.clear()
        assertNull(t.access()); assertNull(t.refresh())
    }

    @Test fun storeTokenStoreKeepsTokensUnderItsOwnPrefixOnly() {
        val kv = MemoryKeyValueStore().also { it.put("theme", "dark") }
        val t = StoreTokenStore(kv)
        t.save("a", "r")
        assertEquals("a", kv.get("auth.access")); assertEquals("r", kv.get("auth.refresh"))
        t.save("a2", null)
        assertEquals("r", t.refresh(), "refresh null giữ cái cũ")
        t.clear()
        assertNull(t.access()); assertNull(t.refresh())
        assertEquals("dark", kv.get("theme"), "xoá phiên không đụng tới khoá khác trong cùng kho")
    }

    @Test fun twoPrefixesDoNotCollide() {
        val kv = MemoryKeyValueStore()
        val a = StoreTokenStore(kv, "a."); val b = StoreTokenStore(kv, "b.")
        a.save("A", "RA"); b.save("B", "RB")
        assertEquals("A", a.access()); assertEquals("B", b.access())
        a.clear(); assertEquals("B", b.access())
    }

    @Test fun boolAndIntHelpersUseDefaultsWhenMissingOrBroken() {
        val s = MemoryKeyValueStore()
        assertFalse(s.getBool("f")); assertTrue(s.getBool("f", default = true)); assertEquals(7, s.getInt("n", 7))
        s.putBool("f", true); s.putInt("n", 42)
        assertTrue(s.getBool("f")); assertEquals(42, s.getInt("n"))
        s.putBool("f", false); assertFalse(s.getBool("f", default = true))
        s.put("n", "không phải số"); assertEquals(5, s.getInt("n", 5))
    }

    @Test fun jsonHelpersRoundTripAndNeverThrowOnOldData() {
        val s = MemoryKeyValueStore()
        s.putJson("p", Prefs.serializer(), Prefs(1, "y"))
        assertEquals(Prefs(1, "y"), s.getJson("p", Prefs.serializer()))
        s.put("p", """{"a":2,"unknown":true}"""); assertEquals(Prefs(2), s.getJson("p", Prefs.serializer()), "bỏ qua trường lạ")
        s.put("p", "{hỏng"); assertNull(s.getJson("p", Prefs.serializer()))
        s.putJson("p", Prefs.serializer(), null); assertNull(s.get("p"))
    }

    @Test fun serverUrlIsNormalised() {
        assertEquals("https://api.example.com/app", normaliseServerUrl("  api.example.com/app/  "))
        assertEquals("http://10.0.2.2:3000", normaliseServerUrl("http://10.0.2.2:3000///"))
        assertEquals("", normaliseServerUrl("   "))
    }

    @Test fun storedServerUrlFallsBackToDefaultAndRemembersChanges() {
        val kv = MemoryKeyValueStore()
        val u = StoredServerUrl(kv, "https://default.test")
        assertEquals("https://default.test", u.get())
        u.set("dev.test:3000/"); assertEquals("https://dev.test:3000", u.get())
        u.set("  "); assertEquals("https://default.test", u.get(), "xoá ô nhập thì quay về mặc định")
        kv.put("server_url", "   "); assertEquals("https://default.test", u.get(), "giá trị trắng coi như chưa đặt")
    }
}

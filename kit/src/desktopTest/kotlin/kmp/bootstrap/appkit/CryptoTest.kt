package kmp.bootstrap.appkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse

class CryptoTest {
    @Test fun passwordRoundTrip() = runTest {
        val box = AppCrypto.encryptText("xin chào — nội dung bí mật 🔐", "mật khẩu mạnh")
        assertEquals("xin chào — nội dung bí mật 🔐", AppCrypto.decryptText(box, "mật khẩu mạnh"))
        assertFalse(box.contains("bí mật"))
    }

    @Test fun sameTextEncryptsDifferentlyEachTime() = runTest {
        assertNotEquals(AppCrypto.encryptText("a", "p"), AppCrypto.encryptText("a", "p"), "salt + IV ngẫu nhiên")
    }

    @Test fun wrongPasswordOrTamperingFailsInsteadOfReturningGarbage() = runTest {
        val box = AppCrypto.encryptText("secret", "right")
        assertFailsWith<CryptoError> { AppCrypto.decryptText(box, "wrong") }
        val bytes = box.toCharArray(); bytes[bytes.size - 3] = if (bytes[bytes.size - 3] == 'A') 'B' else 'A'
        assertFailsWith<CryptoError> { AppCrypto.decryptText(String(bytes), "right") }
        assertFailsWith<CryptoError> { AppCrypto.decryptText("không phải base64!!", "right") }
    }

    @Test fun rawKeyRoundTripAndKeyLengthIsChecked() = runTest {
        val key = AppCrypto.generateKey()
        assertEquals(32, key.size)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertContentEquals(data, AppCrypto.decrypt(AppCrypto.encrypt(data, key), key))
        assertFailsWith<CryptoError> { AppCrypto.decrypt(AppCrypto.encrypt(data, key), AppCrypto.generateKey()) }
        assertFailsWith<IllegalArgumentException> { AppCrypto.encrypt(data, ByteArray(10)) }
    }

    @Test fun sha256KnownVector() = runTest {
        val hex = AppCrypto.sha256("abc".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }
}

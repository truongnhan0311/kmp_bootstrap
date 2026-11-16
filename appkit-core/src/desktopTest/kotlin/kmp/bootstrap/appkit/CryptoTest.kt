package kmp.bootstrap.appkit

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // RFC 4231, test case 1 và 2 (khoá ngắn hơn khối, khoá là chữ).
    @Test fun hmacSha256Rfc4231() = runTest {
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Hex.encode(AppCrypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())))
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Hex.encode(AppCrypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())))
    }

    // RFC 4231 test case 6: khoá 131 byte, dài hơn khối 64 byte (phải băm khoá trước).
    @Test fun hmacSha256LongKey() = runTest {
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            Hex.encode(AppCrypto.hmacSha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray())))
    }

    @Test fun hexRoundTripAndRejectsBadInput() {
        val bytes = byteArrayOf(0, 1, 0x7f, -128, -1)
        assertEquals("00017f80ff", Hex.encode(bytes))
        assertContentEquals(bytes, Hex.decode("00017F80ff"))
        assertFailsWith<IllegalArgumentException> { Hex.decode("abc") }
        assertFailsWith<IllegalArgumentException> { Hex.decode("zz") }
    }

    @Test fun constantTimeEqualsComparesContent() {
        assertTrue(AppCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(AppCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(AppCrypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    @Test fun randomStringUsesOnlyAlphabetAndIsUnbiased() {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        assertEquals(8, AppCrypto.randomString(8, alphabet).length)
        assertEquals("", AppCrypto.randomString(0, alphabet))
        // Bảng 200 ký tự: 256 % 200 = 56, nên lấy dư ngây thơ cho 56 ký tự đầu xác suất GẤP ĐÔI (43,75% thay vì 28%).
        val wide = (0 until 200).map { Char(0x100 + it) }.joinToString("")
        val sample = AppCrypto.randomString(20_000, wide)
        assertTrue(sample.all { it in wide })
        val firstPart = sample.count { wide.indexOf(it) < 56 }
        assertTrue(firstPart in 5_000..6_200, "lệch phân bố: $firstPart / 20000 rơi vào 56 ký tự đầu (mong đợi ~5600)")
        assertFailsWith<IllegalArgumentException> { AppCrypto.randomString(4, "a") }
    }
}

package kmp.bootstrap.appkit

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vector khoá dùng chung với server Recam (`server/app/recam/device_login_test.go`, tạo bằng OpenSSL).
 * Kiểm đúng thứ máy làm: Android Keystore SHA256withECDSA trên chuỗi challenge UTF-8, khoá công khai SPKI;
 * iOS gửi điểm 65 byte = 65 byte cuối của SPKI.
 */
class DeviceKeyVectorTest {
    private val pub = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEw4z8Rw2tCg2E8/Mol7m4nSEVTPOEY+aetMJQjGI+JfE2DwUzDgHZUZNc6S6F9iXb4Abc+mtU1t1jdejqqay3sA=="
    private val raw = "BMOM/EcNrQoNhPPzKJe5uJ0hFUzzhGPmnrTCUIxiPiXxNg8FMw4B2VGTXOkuhfYl2+AG3PprVNbdY3Xo6qmst7A="
    private val sig = "MEYCIQDdxn7KglBDPi/qb7QSWmlpgQKcCLA5EASBmzPcAzDVtgIhAMo9TXIzLYTwd+PjqeXsaJmIUNP/qJBe9r2oQBofY27s"
    private val challenge = "eyJ1aWQiOiI3IiwiZGlkIjoidmVjdG9yLWRldmljZSIsImV4cCI6MTc2MzI4MDA2MCwibiI6InZlY3Rvci1ub25jZSJ9.c2ln"

    private fun verify(pubB64: String, msg: String, sigB64: String): Boolean {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
        return Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(msg.encodeToByteArray()); verify(Base64.getDecoder().decode(sigB64)) }
    }

    @Test fun serverVectorVerifies() {
        assertTrue(verify(pub, challenge, sig))
        assertFalse(verify(pub, challenge + "x", sig), "chữ ký gắn với đúng chuỗi challenge")
    }

    @Test fun iosRawPointIsSpkiTail() {
        val spki = Base64.getDecoder().decode(pub)
        assertContentEquals(Base64.getDecoder().decode(raw), spki.copyOfRange(spki.size - 65, spki.size))
    }

    @Test fun javaSignatureFormatIsWhatServerExpects() {
        // Cùng API JCA mà Android Keystore dùng: SPKI + DER, base64 chuẩn.
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val s = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(challenge.encodeToByteArray()); sign() }
        assertTrue(s[0] == 0x30.toByte(), "ASN.1 DER SEQUENCE")
        assertTrue(verify(Base64.getEncoder().encodeToString(kp.public.encoded), challenge, Base64.getEncoder().encodeToString(s)))
    }
}

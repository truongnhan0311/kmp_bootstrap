package kmp.bootstrap.appkit

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Mã hoá / giải mã dùng ở bất kỳ đâu, cùng kết quả trên Android, iOS, desktop:
 *
 *     val box = AppCrypto.encryptText("nội dung bí mật", password = "mật khẩu")   // chuỗi base64 an toàn để lưu/gửi
 *     val text = AppCrypto.decryptText(box, password = "mật khẩu")                 // sai mật khẩu hoặc bị sửa → ném CryptoError
 *
 * Thuật toán: AES-256-GCM (mã hoá có xác thực: sửa một byte là giải mã thất bại, không ra rác). Khoá từ mật khẩu:
 * PBKDF2-HMAC-SHA256, 210.000 vòng, salt ngẫu nhiên 16 byte mỗi lần. IV ngẫu nhiên mỗi lần do thư viện sinh và gắn vào đầu
 * bản mã. Dùng thư viện cryptography-kotlin (JDK / Apple / OpenSSL) — KHÔNG tự cài thuật toán.
 *
 * Quy tắc: mật khẩu/khoá không bao giờ ghi log (AppLog che sẵn các trường tên password/secret/token).
 * Muốn lưu bí mật cục bộ thì dùng AppStorage.secrets (Keystore/Keychain), không phải cái này.
 */
class CryptoError(message: String, cause: Throwable? = null) : Exception(message, cause)

object AppCrypto {
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val ITERATIONS = 210_000
    private val provider get() = CryptographyProvider.Default

    /** Khoá AES-256 ngẫu nhiên (32 byte) — cất bằng AppStorage.secrets nếu cần dùng lại. */
    fun generateKey(): ByteArray = CryptographyRandom.nextBytes(32)

    /** Mã hoá byte bằng khoá 32 byte. Kết quả = IV + bản mã + thẻ xác thực. */
    suspend fun encrypt(plain: ByteArray, key: ByteArray): ByteArray = cipherOf(key).encrypt(plain)

    suspend fun decrypt(box: ByteArray, key: ByteArray): ByteArray = try {
        cipherOf(key).decrypt(box)
    } catch (e: Exception) {
        AppLog.w("crypto", "decrypt failed (${e::class.simpleName})") // never log key or data
        throw CryptoError(Strings.text(Msg.DecryptFailed), e)
    }

    /** Mã hoá chuỗi bằng mật khẩu → chuỗi base64 (phiên bản + salt + bản mã). */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun encryptText(text: String, password: String): String {
        val salt = CryptographyRandom.nextBytes(SALT_BYTES)
        val box = encrypt(text.encodeToByteArray(), deriveKey(password, salt))
        return Base64.UrlSafe.encode(byteArrayOf(VERSION) + salt + box)
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun decryptText(token: String, password: String): String {
        val raw = try { Base64.UrlSafe.decode(token) } catch (e: Exception) { throw CryptoError(Strings.text(Msg.BadFormat), e) }
        if (raw.size <= 1 + SALT_BYTES || raw[0] != VERSION) throw CryptoError(Strings.text(Msg.BadFormat))
        val salt = raw.copyOfRange(1, 1 + SALT_BYTES)
        val box = raw.copyOfRange(1 + SALT_BYTES, raw.size)
        return decrypt(box, deriveKey(password, salt)).decodeToString()
    }

    /** SHA-256 (để so sánh/định danh, KHÔNG dùng để băm mật khẩu). */
    suspend fun sha256(data: ByteArray): ByteArray = provider.get(SHA256).hasher().hash(data)

    private suspend fun deriveKey(password: String, salt: ByteArray): ByteArray =
        provider.get(PBKDF2).secretDerivation(SHA256, ITERATIONS, 256.bits, salt).deriveSecret(password.encodeToByteArray()).toByteArray()

    private suspend fun cipherOf(key: ByteArray) = run {
        require(key.size == 32) { "key must be exactly 32 bytes (AES-256)" }
        provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher()
    }
}

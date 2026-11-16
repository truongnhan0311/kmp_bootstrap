package kmp.bootstrap.appkit

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
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
        throw CryptoError(Language.text(Msg.DecryptFailed), e)
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
        val raw = try { Base64.UrlSafe.decode(token) } catch (e: Exception) { throw CryptoError(Language.text(Msg.BadFormat), e) }
        if (raw.size <= 1 + SALT_BYTES || raw[0] != VERSION) throw CryptoError(Language.text(Msg.BadFormat))
        val salt = raw.copyOfRange(1, 1 + SALT_BYTES)
        val box = raw.copyOfRange(1 + SALT_BYTES, raw.size)
        return decrypt(box, deriveKey(password, salt)).decodeToString()
    }

    /** SHA-256 (để so sánh/định danh, KHÔNG dùng để băm mật khẩu). */
    suspend fun sha256(data: ByteArray): ByteArray = provider.get(SHA256).hasher().hash(data)

    /** HMAC-SHA256 (RFC 2104): ký/kiểm request với khoá chung. Khoá dài bao nhiêu cũng được. So sánh chữ ký bằng [constantTimeEquals]. */
    suspend fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        provider.get(HMAC).keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, key).signatureGenerator().generateSignature(data)

    /** So sánh không lộ thời gian (dùng khi kiểm chữ ký/mã): thời gian chạy không phụ thuộc vị trí byte khác đầu tiên. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * Chuỗi ngẫu nhiên an toàn (CSPRNG) gồm [length] ký tự lấy đều từ [alphabet] (không lệch: bỏ byte rơi vào phần dư).
     * Ví dụ mã ghép đôi dễ đọc: `randomString(8, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789")`.
     */
    fun randomString(length: Int, alphabet: String): String {
        require(length >= 0) { "length must be >= 0" }
        require(alphabet.length in 2..256) { "alphabet must have 2..256 characters" }
        val limit = 256 - 256 % alphabet.length   // byte >= limit bị bỏ để mọi ký tự có cùng xác suất
        val out = StringBuilder(length)
        while (out.length < length) {
            for (b in CryptographyRandom.nextBytes(length * 2)) {
                val v = b.toInt() and 0xff
                if (v < limit && out.length < length) out.append(alphabet[v % alphabet.length])
            }
        }
        return out.toString()
    }

    private suspend fun deriveKey(password: String, salt: ByteArray): ByteArray =
        provider.get(PBKDF2).secretDerivation(SHA256, ITERATIONS, 256.bits, salt).deriveSecret(password.encodeToByteArray()).toByteArray()

    private suspend fun cipherOf(key: ByteArray) = run {
        require(key.size == 32) { "key must be exactly 32 bytes (AES-256)" }
        provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key).cipher()
    }
}

/** Hex chữ thường, như `hex.EncodeToString` của Go và phần lớn server. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b -> val v = b.toInt() and 0xff; out[2 * i] = DIGITS[v shr 4]; out[2 * i + 1] = DIGITS[v and 0xf] }
        return out.concatToString()
    }

    /** Nhận cả chữ hoa lẫn thường; chuỗi lẻ hoặc có ký tự lạ → [IllegalArgumentException]. */
    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "hex length must be even" }
        return ByteArray(text.length / 2) { i ->
            val hi = text[2 * i].digitToIntOrNull(16); val lo = text[2 * i + 1].digitToIntOrNull(16)
            require(hi != null && lo != null) { "invalid hex character" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

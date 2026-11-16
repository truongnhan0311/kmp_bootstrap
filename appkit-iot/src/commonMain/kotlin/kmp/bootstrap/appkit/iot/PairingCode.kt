package kmp.bootstrap.appkit.iot

import kmp.bootstrap.appkit.AppCrypto

/*
 * Mã ghép đôi người đọc và gõ được (kiểu token kích hoạt của Tuya): mặc định 8 ký tự từ bảng 32 ký tự bỏ 0/O/1/I,
 * hiện thành hai nhóm "K7Q2 M9XA". Người dùng gõ thường/hoa, có dấu cách hay gạch nối đều được.
 * Mã do SERVER sinh và giữ hạn dùng (vd 10 phút, một lần); app chỉ hiển thị, đọc lại và kiểm dạng trước khi gửi.
 */
class PairingCode(
    val alphabet: String = DEFAULT_ALPHABET,
    val length: Int = 8,
    val groupSize: Int = 4,
) {
    init {
        require(alphabet.length >= 2 && alphabet.toSet().size == alphabet.length) { "alphabet must have at least 2 distinct characters" }
        require(alphabet == alphabet.uppercase()) { "alphabet must be upper case" }
        require(length > 0 && groupSize > 0) { "length and groupSize must be positive" }
    }

    /** Mã ngẫu nhiên an toàn (dùng ở server viết bằng Kotlin, hoặc khi test). */
    fun generate(): String = AppCrypto.randomString(length, alphabet)

    /** "K7Q2M9XA" → "K7Q2 M9XA". */
    fun format(code: String): String = code.chunked(groupSize).joinToString(" ")

    /** Chuẩn hoá thứ người dùng gõ hoặc quét được; `null` nếu sai độ dài hay có ký tự ngoài bảng. */
    fun normalize(input: String): String? {
        val code = input.filterNot { it.isWhitespace() || it == '-' }.uppercase()
        return code.takeIf { it.length == length && it.all { c -> c in alphabet } }
    }

    companion object {
        const val DEFAULT_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val Default = PairingCode()
    }
}

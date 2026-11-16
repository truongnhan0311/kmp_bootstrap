package kmp.bootstrap.appkit

/*
 * Nén gzip thuần Kotlin (RFC 1951 DEFLATE + RFC 1952 gzip), chạy giống nhau trên Android, iOS, desktop — không cần zlib của nền tảng.
 *
 *     val gz = Gzip.compress(text.encodeToByteArray())     // mở được bằng gunzip, GZIPInputStream, máy chủ bất kỳ
 *
 * Dùng LZ77 (cửa sổ 32 KiB, bảng băm 3 byte) + mã Huffman CỐ ĐỊNH của chuẩn: đơn giản, không cần bảng động, nén văn bản log
 * khoảng 3–6 lần (kém zlib -6 một chút). Chỉ có NÉN; giải nén để dành cho thư viện của máy chủ.
 */
object Gzip {
    fun compress(data: ByteArray): ByteArray {
        val out = BitWriter(data.size / 2 + 64)
        // Đầu gzip: 1f 8b, CM=8 (deflate), FLG=0, MTIME=0, XFL=0, OS=255 (không rõ).
        intArrayOf(0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 0xff).forEach { out.byte(it) }
        deflateFixed(data, out)
        out.alignToByte()
        val crc = crc32(data)
        for (i in 0 until 4) out.byte((crc ushr (8 * i)) and 0xff)
        for (i in 0 until 4) out.byte((data.size ushr (8 * i)) and 0xff)
        return out.toByteArray()
    }

    /** CRC-32 (IEEE 802.3), như của gzip/zip. */
    fun crc32(data: ByteArray): Int {
        var c = -1
        for (b in data) c = CRC_TABLE[(c xor b.toInt()) and 0xff] xor (c ushr 8)
        return c.inv()
    }

    private const val WINDOW = 32768
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 258
    private const val MAX_CHAIN = 64
    private const val HASH_BITS = 15

    private fun deflateFixed(data: ByteArray, out: BitWriter) {
        out.bits(1, 1)          // BFINAL: khối cuối (một khối cho cả dữ liệu)
        out.bits(1, 2)          // BTYPE = 01: Huffman cố định
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val prev = IntArray(WINDOW)
        fun hash(i: Int) = (((data[i].toInt() and 0xff) shl 10) xor ((data[i + 1].toInt() and 0xff) shl 5) xor (data[i + 2].toInt() and 0xff)) and ((1 shl HASH_BITS) - 1)
        fun insert(i: Int) { if (i + MIN_MATCH <= data.size) { val h = hash(i); prev[i and (WINDOW - 1)] = head[h]; head[h] = i } }

        var i = 0
        while (i < data.size) {
            var bestLen = 0; var bestDist = 0
            if (i + MIN_MATCH <= data.size) {
                var cand = head[hash(i)]
                var chain = MAX_CHAIN
                val limit = minOf(MAX_MATCH, data.size - i)
                while (cand >= 0 && i - cand <= WINDOW && chain-- > 0) {
                    var l = 0
                    while (l < limit && data[cand + l] == data[i + l]) l++
                    if (l > bestLen) { bestLen = l; bestDist = i - cand; if (l == limit) break }
                    val p = prev[cand and (WINDOW - 1)]
                    if (p >= cand) break   // ô đã bị ghi đè bởi vị trí mới hơn: hết chuỗi hợp lệ
                    cand = p
                }
            }
            if (bestLen >= MIN_MATCH) {
                writeLength(out, bestLen); writeDistance(out, bestDist)
                for (k in 0 until bestLen) insert(i + k)
                i += bestLen
            } else {
                writeLiteral(out, data[i].toInt() and 0xff)
                insert(i); i++
            }
        }
        writeLiteral(out, 256)  // hết khối
    }

    /** Mã Huffman cố định (RFC 1951 mục 3.2.6) ghi từ bit cao nhất của mã. */
    private fun writeLiteral(out: BitWriter, v: Int) = when {
        v <= 143 -> out.code(0x30 + v, 8)
        v <= 255 -> out.code(0x190 + v - 144, 9)
        v <= 279 -> out.code(v - 256, 7)
        else -> out.code(0xC0 + v - 280, 8)
    }

    private fun writeLength(out: BitWriter, len: Int) {
        var c = LEN_BASE.size - 1
        while (LEN_BASE[c] > len) c--
        writeLiteral(out, 257 + c)
        if (LEN_EXTRA[c] > 0) out.bits(len - LEN_BASE[c], LEN_EXTRA[c])
    }

    private fun writeDistance(out: BitWriter, dist: Int) {
        var c = DIST_BASE.size - 1
        while (DIST_BASE[c] > dist) c--
        out.code(c, 5)
        if (DIST_EXTRA[c] > 0) out.bits(dist - DIST_BASE[c], DIST_EXTRA[c])
    }

    private val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /** Ghi bit theo thứ tự của DEFLATE: bit thấp trước trong mỗi byte. */
    private class BitWriter(capacity: Int) {
        private var buf = ByteArray(maxOf(capacity, 64)); private var size = 0
        private var acc = 0; private var n = 0
        private fun put(b: Int) { if (size == buf.size) buf = buf.copyOf(buf.size * 2); buf[size++] = b.toByte() }
        fun bits(value: Int, count: Int) {
            acc = acc or (value shl n); n += count
            while (n >= 8) { put(acc and 0xff); acc = acc ushr 8; n -= 8 }
        }
        /** Mã Huffman: đảo thứ tự bit vì DEFLATE ghi mã từ bit cao nhất. */
        fun code(value: Int, len: Int) { var r = 0; for (i in 0 until len) r = (r shl 1) or ((value ushr i) and 1); bits(r, len) }
        fun alignToByte() { if (n > 0) { put(acc and 0xff); acc = 0; n = 0 } }
        fun byte(b: Int) { alignToByte(); put(b) }
        fun toByteArray() = buf.copyOf(size)
    }
}

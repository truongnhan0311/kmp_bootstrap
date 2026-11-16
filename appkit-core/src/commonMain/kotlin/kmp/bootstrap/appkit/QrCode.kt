package kmp.bootstrap.appkit

import kotlin.math.abs

/*
 * Sinh mã QR thuần Kotlin (ISO/IEC 18004, Model 2), chạy giống nhau trên Android, iOS, desktop:
 *
 *     val qr = QrCode.encode("https://app.example.com/i/7FhQ2kLm")      // chọn phiên bản nhỏ nhất vừa, nâng mức sửa lỗi nếu còn chỗ
 *     qr.size; qr[x, y]                                                   // ma trận ô (true = đen), chưa có viền trắng
 *     val svg = qr.toSvg()                                                // hoặc vẽ bằng QrImage của appkit-ui
 *
 * Chế độ byte (UTF-8), 4 mức sửa lỗi, phiên bản 1–40, tự chọn mặt nạ theo điểm phạt của chuẩn.
 * Cách tổ chức thuật toán theo "QR Code generator library" của Project Nayuki (MIT License,
 * Copyright (c) Project Nayuki, https://www.nayuki.io/page/qr-code-generator-library).
 */

/** Mức sửa lỗi: khôi phục được khoảng 7% / 15% / 25% / 30% mã bị bẩn hay che. */
enum class QrEcc(internal val formatBits: Int) { Low(1), Medium(0), Quartile(3), High(2) }

class QrCode private constructor(val version: Int, val ecc: QrEcc, dataCodewords: ByteArray, forcedMask: Int?) {
    val size: Int = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    /** Mặt nạ đã dùng (0–7). */
    val mask: Int

    init {
        drawFunctionPatterns()
        drawCodewords(addEccAndInterleave(dataCodewords))
        mask = forcedMask ?: (0..7).minBy { m -> applyMask(m); drawFormatBits(m); penalty().also { applyMask(m) } }
        applyMask(mask)
        drawFormatBits(mask)
    }

    /** Ô (x, y) có đen không. Ngoài ma trận → `false` (viền trắng). */
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    /** SVG vuông, mỗi ô một đơn vị, [border] ô trắng quanh mã (chuẩn yêu cầu ít nhất 4). */
    fun toSvg(border: Int = 4, dark: String = "#000000", light: String = "#FFFFFF"): String {
        require(border >= 0) { "border must be >= 0" }
        val n = size + border * 2
        val path = buildString {
            for (y in 0 until size) for (x in 0 until size) if (modules[y][x]) append("M${x + border},${y + border}h1v1h-1z")
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $n $n" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="$light"/><path d="$path" fill="$dark"/></svg>"""
    }

    // ---------------------------------------------------------------- vẽ phần cố định

    private fun drawFunctionPatterns() {
        for (i in 0 until size) { setFunction(6, i, i % 2 == 0); setFunction(i, 6, i % 2 == 0) }
        drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
        val align = alignmentPositions(version)
        val last = align.size - 1
        for (i in align.indices) for (j in align.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
            drawAlignment(align[i], align[j])
        }
        drawFormatBits(0)   // giữ chỗ; vẽ lại khi có mặt nạ
        drawVersion()
    }

    private fun drawFinder(x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val dist = maxOf(abs(dx), abs(dy))
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until size && yy in 0 until size) setFunction(xx, yy, dist != 2 && dist != 4)
        }
    }

    private fun drawAlignment(x: Int, y: Int) {
        for (dy in -2..2) for (dx in -2..2) setFunction(x + dx, y + dy, maxOf(abs(dx), abs(dy)) != 1)
    }

    private fun drawFormatBits(mask: Int) {
        val data = ecc.formatBits shl 3 or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = (data shl 10 or rem) xor 0x5412
        for (i in 0..5) setFunction(8, i, bit(bits, i))
        setFunction(8, 7, bit(bits, 6)); setFunction(8, 8, bit(bits, 7)); setFunction(7, 8, bit(bits, 8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(bits, i))
        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(bits, i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(bits, i))
        setFunction(8, size - 8, true)   // ô luôn đen
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = version shl 12 or rem
        for (i in 0 until 18) {
            val b = bit(bits, i); val a = size - 11 + i % 3; val c = i / 3
            setFunction(a, c, b); setFunction(c, a, b)
        }
    }

    private fun setFunction(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

    // ---------------------------------------------------------------- dữ liệu

    private fun addEccAndInterleave(data: ByteArray): ByteArray {
        val numBlocks = BLOCKS[ecc.ordinal][version]
        val eccLen = ECC_PER_BLOCK[ecc.ordinal][version]
        val raw = rawDataModules(version) / 8
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val divisor = rsDivisor(eccLen)
        val blocks = ArrayList<ByteArray>(numBlocks)
        var k = 0
        for (i in 0 until numBlocks) {
            val dat = data.copyOfRange(k, k + shortLen - eccLen + if (i < numShort) 0 else 1)
            k += dat.size
            val block = ByteArray(shortLen + 1)
            dat.copyInto(block)
            rsRemainder(dat, divisor).copyInto(block, block.size - eccLen)
            blocks += block
        }
        val out = ByteArray(raw); var o = 0
        for (i in 0 until shortLen + 1) for (j in blocks.indices) {
            if (i != shortLen - eccLen || j >= numShort) out[o++] = blocks[j][i]   // khối ngắn: bỏ ô đệm
        }
        return out
    }

    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = (right + 1) and 2 == 0
                val y = if (upward) size - 1 - vert else vert
                if (!isFunction[y][x] && i < data.size * 8) { modules[y][x] = bit(data[i ushr 3].toInt(), 7 - (i and 7)); i++ }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
        }
    }

    /** Điểm phạt theo chuẩn (mục 7.8.3): dải cùng màu, khối 2×2, mẫu giống ô định vị, tỉ lệ đen/trắng. */
    private fun penalty(): Int {
        var result = 0
        for (horizontal in listOf(true, false)) {
            for (a in 0 until size) {
                var runColor = false; var run = 0
                val line = BooleanArray(size) { b -> if (horizontal) modules[a][b] else modules[b][a] }
                for (b in 0 until size) {
                    if (line[b] == runColor) { run++; if (run == 5) result += 3 else if (run > 5) result++ } else { runColor = line[b]; run = 1 }
                }
                // 1:1:3:1:1 có 4 ô trắng một bên (ngoài mép tính là trắng)
                for (b in -4 until size) {
                    fun at(i: Int) = i in 0 until size && line[i]
                    val core = at(b) && !at(b + 1) && at(b + 2) && at(b + 3) && at(b + 4) && !at(b + 5) && at(b + 6)
                    if (!core) continue
                    val before = (b - 4 until b).none { at(it) }
                    val after = (b + 7 until b + 11).none { at(it) }
                    if (before || after) result += 40
                }
            }
        }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = ((abs(dark * 20 - total * 10) + total - 1) / total) - 1
        result += k * 10
        return result
    }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        /** Mã hoá chuỗi (UTF-8). [boostEcc]: nếu phiên bản đã chọn còn chỗ thì nâng mức sửa lỗi lên, không làm mã to hơn. */
        fun encode(text: String, ecc: QrEcc = QrEcc.Medium, boostEcc: Boolean = true): QrCode =
            encodeBytes(text.encodeToByteArray(), ecc, boostEcc)

        /**
         * Mã hoá byte tuỳ ý. Dữ liệu quá lớn cho phiên bản 40 → [IllegalArgumentException]
         * (giới hạn: 2953 byte ở mức Low, 2331 Medium, 1663 Quartile, 1273 High).
         */
        fun encodeBytes(data: ByteArray, ecc: QrEcc = QrEcc.Medium, boostEcc: Boolean = true, minVersion: Int = 1, mask: Int? = null): QrCode {
            require(minVersion in MIN_VERSION..MAX_VERSION) { "minVersion must be 1..40" }
            require(mask == null || mask in 0..7) { "mask must be 0..7" }
            val version = (minVersion..MAX_VERSION).firstOrNull { v -> bitsNeeded(data.size, v) <= dataCodewords(v, ecc) * 8 }
                ?: throw IllegalArgumentException("data too long for a QR code: ${data.size} bytes at ecc $ecc")
            var level = ecc
            if (boostEcc) for (e in listOf(QrEcc.Medium, QrEcc.Quartile, QrEcc.High)) {
                if (e.ordinal > level.ordinal && bitsNeeded(data.size, version) <= dataCodewords(version, e) * 8) level = e
            }
            // Chuỗi bit: chế độ byte 0100, độ dài, dữ liệu, kết thúc, đệm.
            val capacityBits = dataCodewords(version, level) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, countBits(version))
            data.forEach { bits.append(it.toInt() and 0xff, 8) }
            bits.append(0, minOf(4, capacityBits - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) { bits.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
            return QrCode(version, level, bits.toBytes(), mask)
        }

        /** Số byte tối đa ở chế độ byte cho [version] và [ecc]. */
        fun byteCapacity(version: Int, ecc: QrEcc): Int = (dataCodewords(version, ecc) * 8 - 4 - countBits(version)) / 8

        private fun countBits(version: Int) = if (version <= 9) 8 else 16
        private fun bitsNeeded(bytes: Int, version: Int): Int =
            if (bytes >= (1 shl countBits(version))) Int.MAX_VALUE else 4 + countBits(version) + bytes * 8

        internal fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val numAlign = version / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        internal fun dataCodewords(version: Int, ecc: QrEcc): Int =
            rawDataModules(version) / 8 - ECC_PER_BLOCK[ecc.ordinal][version] * BLOCKS[ecc.ordinal][version]

        private fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val size = version * 4 + 17
            val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun bit(x: Int, i: Int) = (x ushr i) and 1 != 0

        // Reed–Solomon trên GF(2^8) với đa thức 0x11D.
        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree); result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = gfMul(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xff) xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        // Bảng của chuẩn (bảng 9), chỉ số [mức][phiên bản]; cột 0 không dùng.
        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun append(value: Int, len: Int) { for (i in len - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
        fun toBytes(): ByteArray = ByteArray(bits.size / 8) { b -> var v = 0; for (i in 0 until 8) v = v shl 1 or (if (bits[b * 8 + i]) 1 else 0); v.toByte() }
    }
}

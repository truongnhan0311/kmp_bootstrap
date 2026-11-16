package kmp.bootstrap.appkit

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Mọi mã sinh ra được giải lại bằng ZXing (bộ giải mã độc lập, dùng rộng rãi): sai bảng, RS hay vị trí ô là không đọc được. */
class QrCodeTest {
    private val levelName = mapOf(QrEcc.Low to "L", QrEcc.Medium to "M", QrEcc.Quartile to "Q", QrEcc.High to "H")

    private fun decode(qr: QrCode, scale: Int = 3, border: Int = 4): Pair<String, String> {
        val n = (qr.size + border * 2) * scale
        val px = IntArray(n * n) { i -> if (qr[(i % n) / scale - border, (i / n) / scale - border]) 0xff000000.toInt() else -1 }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(n, n, px)))
        val hints = mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "UTF-8",
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        val r = QRCodeReader().decode(bitmap, hints)
        return r.text to r.resultMetadata[ResultMetadataType.ERROR_CORRECTION_LEVEL].toString()
    }

    @Test fun everyVersionAndLevelAtFullCapacityDecodes() {
        val rnd = Random(42)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_/:."
        for (ecc in QrEcc.entries) for (v in 1..40) {
            val cap = QrCode.byteCapacity(v, ecc)
            val text = String(CharArray(cap) { alphabet[rnd.nextInt(alphabet.length)] })
            val qr = QrCode.encode(text, ecc, boostEcc = false)
            assertEquals(v, qr.version, "$ecc v$v: đầy đúng phiên bản này")
            assertEquals(text to levelName[ecc], decode(qr, scale = if (v > 25) 2 else 3), "$ecc v$v")
            if (v < 40) assertEquals(v + 1, QrCode.encode(text + "x", ecc, boostEcc = false).version, "thêm 1 byte phải lên phiên bản sau")
        }
    }

    @Test fun formatBitsMatchTheStandardTable() {
        // Phụ lục C của chuẩn: chuỗi 15 bit định dạng (đã che bằng 101010000010010) cho mặt nạ 0.
        // Phải kiểm trực tiếp: ZXing cố ý đọc được cả mã QUÊN che, nên giải mã thành công chưa đủ chứng minh.
        val want = mapOf(QrEcc.Low to "111011111000100", QrEcc.Medium to "101010000010010", QrEcc.Quartile to "011010101011111", QrEcc.High to "001011010001001")
        for ((ecc, bits) in want) {
            val qr = QrCode.encodeBytes("x".encodeToByteArray(), ecc, boostEcc = false, mask = 0)
            val cells = (0..5).map { 8 to it } + listOf(8 to 7, 8 to 8, 7 to 8) + (9 until 15).map { (14 - it) to 8 }   // bit 0 → bit 14
            val read = cells.reversed().joinToString("") { (x, y) -> if (qr[x, y]) "1" else "0" }
            assertEquals(bits, read, "$ecc")
            val copy = ((0 until 8).map { (qr.size - 1 - it) to 8 } + (8 until 15).map { 8 to (qr.size - 15 + it) }).reversed()
            assertEquals(bits, copy.joinToString("") { (x, y) -> if (qr[x, y]) "1" else "0" }, "$ecc bản sao thứ hai")
        }
    }

    @Test fun knownCapacitiesFromTheStandard() {
        // Bảng 7 của ISO/IEC 18004, chế độ byte.
        assertEquals(17, QrCode.byteCapacity(1, QrEcc.Low)); assertEquals(7, QrCode.byteCapacity(1, QrEcc.High))
        assertEquals(2953, QrCode.byteCapacity(40, QrEcc.Low)); assertEquals(2331, QrCode.byteCapacity(40, QrEcc.Medium))
        assertEquals(1663, QrCode.byteCapacity(40, QrEcc.Quartile)); assertEquals(1273, QrCode.byteCapacity(40, QrEcc.High))
        assertEquals(26, QrCode.rawDataModules(1) / 8); assertEquals(3706, QrCode.rawDataModules(40) / 8)
    }

    @Test fun unicodeAllMasksAndBoost() {
        val text = "Mời xem camera Phòng em bé 👶 https://recam.app/i/7FhQ2kLm"
        for (m in 0..7) assertEquals(text, decode(QrCode.encodeBytes(text.encodeToByteArray(), mask = m)).first, "mặt nạ $m")
        val small = QrCode.encode("K7Q2M9XA", QrEcc.Low)
        assertEquals(1, small.version); assertEquals(QrEcc.Quartile, small.ecc, "còn chỗ thì nâng mức sửa lỗi (8 byte > 7 byte của mức High v1)")
        assertEquals(21, small.size)
        assertEquals("Q", decode(small).second)
    }

    @Test fun limitsAndSvg() {
        assertFailsWith<IllegalArgumentException> { QrCode.encodeBytes(ByteArray(2954), QrEcc.Low) }
        assertEquals(40, QrCode.encodeBytes(ByteArray(2953), QrEcc.Low).version)
        assertEquals(5, QrCode.encodeBytes("a".encodeToByteArray(), minVersion = 5).version)
        val svg = QrCode.encode("hi").toSvg(border = 2)
        assertTrue(svg.startsWith("<svg") && "viewBox=\"0 0 25 25\"" in svg && "M2,2h1v1h-1z" in svg, "ô góc trên trái (ô định vị) đen, lệch 2 ô viền")
    }
}

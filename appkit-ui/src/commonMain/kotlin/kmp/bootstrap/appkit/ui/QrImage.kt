package kmp.bootstrap.appkit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kmp.bootstrap.appkit.QrCode
import kmp.bootstrap.appkit.QrEcc

/*
 * Vẽ mã QR (sinh bằng QrCode của appkit-core) — đen trên nền trắng THEO MẶC ĐỊNH CẢ Ở GIAO DIỆN TỐI: nhiều máy quét không đọc
 * được mã đảo màu. Viền trắng [border] ô (chuẩn yêu cầu ≥ 4). Mã chỉ tính lại khi [text]/[ecc] đổi.
 *
 *     QrImage("https://app.example.com/i/7FhQ2kLm", size = 220.dp, description = "Mã mời xem camera")
 */
@Composable
fun QrImage(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    ecc: QrEcc = QrEcc.Medium,
    border: Int = 4,
    dark: Color = Color.Black,
    light: Color = Color.White,
    description: String? = null,
) {
    val qr = remember(text, ecc) { QrCode.encode(text, ecc) }
    val desc = description
    Canvas(modifier.size(size).let { m -> if (desc != null) m.semantics { contentDescription = desc } else m }) {
        val n = qr.size + border * 2
        val cell = this.size.minDimension / n
        drawRect(light, Offset.Zero, Size(cell * n, cell * n))
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr[x, y]) drawRect(dark, Offset((x + border) * cell, (y + border) * cell), Size(cell + 0.5f, cell + 0.5f))   // +0.5: không hở khe giữa ô
        }
    }
}

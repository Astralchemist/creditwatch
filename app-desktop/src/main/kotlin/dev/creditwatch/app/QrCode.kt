package dev.creditwatch.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/**
 * A QR code drawn as squares rather than decoded from an image file, so pairing needs no
 * asset pipeline and stays crisp at any size. Modules are snapped to whole pixels: a scanner
 * reads a blurred edge far less reliably than a hard one.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    foreground: Color = Color.Black,
    background: Color = Color.White,
) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(
                    EncodeHintType.MARGIN to 1,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                ),
            )
        }.getOrNull()
    }
    if (matrix == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Could not draw the pairing code", color = foreground, fontSize = 10.sp)
        }
        return
    }

    Canvas(modifier) {
        val modules = matrix.width
        val scale = floor(minOf(size.width, size.height) / modules).coerceAtLeast(1f)
        val drawn = scale * modules
        val left = (size.width - drawn) / 2f
        val top = (size.height - drawn) / 2f

        drawRect(background, topLeft = Offset(left, top), size = Size(drawn, drawn))
        for (row in 0 until matrix.height) {
            for (column in 0 until modules) {
                if (!matrix.get(column, row)) continue
                drawRect(
                    foreground,
                    topLeft = Offset(left + column * scale, top + row * scale),
                    size = Size(scale, scale),
                )
            }
        }
    }
}

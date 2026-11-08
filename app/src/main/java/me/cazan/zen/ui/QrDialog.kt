package me.cazan.zen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Encodes [text] as a QR matrix with no built-in margin (the white card provides the quiet zone). */
private fun qrMatrix(text: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
}.getOrNull()

/** Dark modules on a white card with a generous quiet zone, so any phone camera reads it. */
@Composable
fun QrCode(text: String, size: Dp = 196.dp) {
    val matrix = remember(text) { qrMatrix(text) }
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (matrix != null) {
            Canvas(Modifier.size(size)) {
                val n = matrix.width
                val cell = this.size.minDimension / n
                for (y in 0 until n) for (x in 0 until n) {
                    if (matrix[x, y]) {
                        // Slight overlap avoids hairline seams between modules.
                        drawRect(Color(0xFF0E1116), Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
                    }
                }
            }
        } else {
            Txt("QR unavailable", color = Color.Black)
        }
    }
}

/**
 * Pop-up shown in the middle of the screen when Web Admin is turned on: a QR code for the
 * phone's camera, with the address and PIN underneath for typing it by hand.
 */
@Composable
fun WebAdminQrDialog(url: String, pin: String, onClose: () -> Unit, onTurnOff: () -> Unit) {
    val first = remember { FocusRequester() }
    ZenDialog("Web Admin", onClose, width = 620.dp, initialFocus = first, scrollable = false) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Note("Scan with your phone's camera", color = Zen.TextDim)
            Spacer(Modifier.height(10.dp))
            QrCode(url)
            Spacer(Modifier.height(12.dp))
            Txt(url, size = 22.sp, color = Zen.Accent, align = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Txt("PIN", size = 18.sp, color = Zen.TextDim)
                Txt(pin.chunked(3).joinToString(" "), size = 38.sp, weight = FontWeight.Medium)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ZenButton("Close", onClose, Modifier.focusRequester(first))
                ZenButton("Turn off Web Admin", onTurnOff)
            }
            Spacer(Modifier.height(10.dp))
            Txt("Closing keeps the page on while Settings is open", size = 13.sp, color = Zen.TextFaint)
        }
    }
}

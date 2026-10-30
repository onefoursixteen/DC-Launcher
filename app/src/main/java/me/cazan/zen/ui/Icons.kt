package me.cazan.zen.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

// Hand-drawn icons: avoids pulling in material-icons (several MB before shrinking).

@Composable
fun GearIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val r = size.minDimension / 2
        repeat(8) { i ->
            rotate(i * 45f) {
                drawRoundRect(
                    color,
                    topLeft = Offset(center.x - r * 0.15f, center.y - r),
                    size = Size(r * 0.3f, r * 0.45f),
                    cornerRadius = CornerRadius(r * 0.06f),
                )
            }
        }
        drawCircle(color, radius = r * 0.52f, style = Stroke(width = r * 0.3f))
    }
}

@Composable
fun SlidersIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val stroke = w * 0.09f
        listOf(0.2f to 0.68f, 0.5f to 0.3f, 0.8f to 0.58f).forEach { (y, knob) ->
            drawLine(color, Offset(w * 0.08f, w * y), Offset(w * 0.92f, w * y), stroke, StrokeCap.Round)
            drawCircle(color, radius = w * 0.11f, center = Offset(w * knob, w * y))
        }
    }
}

@Composable
fun WifiIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val origin = Offset(w / 2, w * 0.86f)
        listOf(0.82f, 0.56f, 0.30f).forEach { f ->
            val r = w * f
            drawArc(
                color, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(origin.x - r, origin.y - r), size = Size(r * 2, r * 2),
                style = Stroke(width = w * 0.1f, cap = StrokeCap.Round),
            )
        }
        drawCircle(color, radius = w * 0.07f, center = origin)
    }
}

@Composable
fun EthernetIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val stroke = w * 0.09f
        drawRoundRect(
            color, topLeft = Offset(w * 0.15f, w * 0.25f), size = Size(w * 0.7f, w * 0.5f),
            cornerRadius = CornerRadius(w * 0.08f), style = Stroke(stroke),
        )
        drawRect(color, topLeft = Offset(w * 0.38f, w * 0.75f), size = Size(w * 0.24f, w * 0.15f))
        listOf(0.33f, 0.5f, 0.67f).forEach { x ->
            drawLine(color, Offset(w * x, w * 0.36f), Offset(w * x, w * 0.5f), stroke * 0.8f, StrokeCap.Round)
        }
    }
}

@Composable
fun OfflineIcon(color: Color, modifier: Modifier = Modifier) {
    WifiIcon(color.copy(alpha = color.alpha * 0.45f), modifier)
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        drawLine(color, Offset(w * 0.1f, w * 0.1f), Offset(w * 0.9f, w * 0.9f), w * 0.1f, StrokeCap.Round)
    }
}

/** Shield with a keyhole: shown instead of Wi-Fi/Ethernet while a VPN is active. */
@Composable
fun VpnIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(22.dp)) {
        val w = size.width
        val shield = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, w * 0.04f)
            lineTo(w * 0.88f, w * 0.18f)
            lineTo(w * 0.88f, w * 0.48f)
            cubicTo(w * 0.88f, w * 0.74f, w * 0.7f, w * 0.88f, w * 0.5f, w * 0.97f)
            cubicTo(w * 0.3f, w * 0.88f, w * 0.12f, w * 0.74f, w * 0.12f, w * 0.48f)
            lineTo(w * 0.12f, w * 0.18f)
            close()
        }
        drawPath(shield, color)
        // Keyhole in the opposite tone, so it stays visible on a focused (light) button too.
        val hole = if (color.luminance() < 0.5f) Color(0xFFF2F4F8) else Color(0xFF0E1116)
        drawCircle(hole, radius = w * 0.1f, center = Offset(w * 0.5f, w * 0.42f))
        drawRect(hole, topLeft = Offset(w * 0.46f, w * 0.44f), size = Size(w * 0.08f, w * 0.22f))
    }
}

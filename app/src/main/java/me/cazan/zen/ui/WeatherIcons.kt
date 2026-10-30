package me.cazan.zen.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SHOWERS, SNOW, SNOW_SHOWERS, THUNDER }

/** WMO weather code (Open-Meteo) → what to draw. */
fun skyFor(code: Int): Sky = when (code) {
    0, 1 -> Sky.CLEAR
    2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67 -> Sky.RAIN
    in 80..82 -> Sky.SHOWERS
    in 71..77 -> Sky.SNOW
    85, 86 -> Sky.SNOW_SHOWERS
    in 95..99 -> Sky.THUNDER
    else -> Sky.CLOUDY
}

private val Sun = Color(0xFFFFC94D)
private val Moon = Color(0xFFE8EDF8)
private val CloudLight = Color(0xFFDDE5F0)
private val CloudMid = Color(0xFFB9C6D8)
private val CloudDark = Color(0xFF8F9CB0)
private val CloudStorm = Color(0xFF7D8AA1)
private val Drop = Color(0xFF6FB6FF)
private val Bolt = Color(0xFFFFD84D)

/**
 * Hand-drawn weather icon, day or night, gently animated. One slow 12-second clock drives
 * every motion, and it's only read inside the draw phase, so animating costs a redraw of this
 * small canvas, never a recomposition. With [animate] off it draws one still frame.
 */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, animate: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val sky = skyFor(code)
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "weather").animateFloat(
            0f, 1f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart), label = "phase",
        )
    } else {
        remember { mutableFloatStateOf(0.3f) }
    }
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 40f // icons are designed on a 40×40 grid
        val t = phase.value
        val still = !animate
        when (sky) {
            Sky.CLEAR -> if (isDay) sun(u, 20f, 20f, 7.5f, t) else moonAndStars(u, t)
            Sky.PARTLY -> {
                if (isDay) sun(u, 15f, 15f, 6f, t) else moon(u, 17f, 14f, 8f)
                cloud(u, 9f, 15f, 27f, CloudLight, drift(t, 1f))
            }
            Sky.CLOUDY -> {
                cloud(u, 4f, 7f, 21f, CloudDark, drift(t + 0.3f, 1f))
                cloud(u, 10f, 15f, 27f, CloudLight, drift(t, 1f))
            }
            Sky.FOG -> fog(u, t)
            Sky.DRIZZLE -> { cloud(u, 7f, 5f, 26f, CloudMid, drift(t, 0.6f)); drops(u, t, still, count = 2, length = 2.5f) }
            Sky.RAIN -> { cloud(u, 7f, 5f, 26f, CloudMid, drift(t, 0.6f)); drops(u, t, still, count = 3, length = 4f) }
            Sky.SHOWERS -> {
                if (isDay) sun(u, 28f, 10f, 5f, t) else moon(u, 28f, 10f, 6f)
                cloud(u, 5f, 7f, 26f, CloudMid, drift(t, 0.6f)); drops(u, t, still, count = 3, length = 4f)
            }
            Sky.SNOW -> { cloud(u, 7f, 5f, 26f, CloudLight, drift(t, 0.6f)); flakes(u, t, still) }
            Sky.SNOW_SHOWERS -> {
                if (isDay) sun(u, 28f, 10f, 5f, t) else moon(u, 28f, 10f, 6f)
                cloud(u, 5f, 7f, 26f, CloudLight, drift(t, 0.6f)); flakes(u, t, still)
            }
            Sky.THUNDER -> {
                cloud(u, 7f, 4f, 26f, CloudStorm, drift(t, 0.6f))
                drops(u, t, still, count = 2, length = 3f)
                lightning(u, t, still)
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

private fun frac(x: Float) = x - kotlin.math.floor(x)

/** Small side-to-side drift (in grid units), one cycle per 6 s. */
private fun drift(t: Float, amount: Float) = sin(frac(t * 2f) * 2f * PI.toFloat()) * amount

private fun DrawScope.sun(u: Float, cx: Float, cy: Float, r: Float, t: Float) {
    val c = Offset(cx * u, cy * u)
    rotate(t * 30f, c) { // a full turn would be busy; 30° per 12 s is just alive
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            val inner = r + 2.2f; val outer = r + 5.2f
            drawLine(
                Sun,
                Offset((cx + cos(a) * inner) * u, (cy + sin(a) * inner) * u),
                Offset((cx + cos(a) * outer) * u, (cy + sin(a) * outer) * u),
                strokeWidth = 2.2f * u, cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(Sun, r * u, c)
}

private fun DrawScope.moon(u: Float, cx: Float, cy: Float, r: Float) {
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx * u, cy * u), r * u)) }
    val bite = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset((cx + r * 0.55f) * u, (cy - r * 0.35f) * u), r * 0.85f * u))
    }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Moon)
}

private fun DrawScope.moonAndStars(u: Float, t: Float) {
    moon(u, 22f, 21f, 11f)
    listOf(Triple(8f, 9f, 1.3f) to 0f, Triple(14f, 5f, 1f) to 0.4f, Triple(6f, 18f, 0.9f) to 0.7f).forEach { (s, off) ->
        val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(frac(t * 4f + off) * 2f * PI.toFloat()))
        drawCircle(Moon.copy(alpha = a), s.third * u, Offset(s.first * u, s.second * u))
    }
}

/** A cloud of width [w] whose top-left is at ([x], [y]); body plus two bumps. */
private fun DrawScope.cloud(u: Float, x: Float, y: Float, w: Float, color: Color, dx: Float) {
    val h = w * 0.55f
    val x0 = x + dx
    drawRoundRect(color, Offset(x0 * u, (y + h * 0.42f) * u), Size(w * u, h * 0.58f * u), CornerRadius(h * 0.29f * u))
    drawCircle(color, h * 0.36f * u, Offset((x0 + w * 0.33f) * u, (y + h * 0.5f) * u))
    drawCircle(color, h * 0.44f * u, Offset((x0 + w * 0.62f) * u, (y + h * 0.42f) * u))
}

private fun DrawScope.drops(u: Float, t: Float, still: Boolean, count: Int, length: Float) {
    val xs = if (count == 2) listOf(15f, 25f) else listOf(13f, 20f, 27f)
    xs.forEachIndexed { i, x ->
        val p = if (still) 0.25f + i * 0.12f else frac(t * 11f + i / count.toFloat()) // ~1.1 s per drop
        val y = 23f + p * 12f
        val a = if (still) 1f else if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f
        drawLine(
            Drop.copy(alpha = a.coerceIn(0f, 1f)),
            Offset(x * u, y * u), Offset((x - length * 0.35f) * u, (y + length) * u),
            strokeWidth = 2.2f * u, cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.flakes(u: Float, t: Float, still: Boolean) {
    listOf(13f, 20f, 27f).forEachIndexed { i, x ->
        val p = if (still) 0.2f + i * 0.15f else frac(t * 5.5f + i / 3f) // ~2.2 s per flake
        val y = 24f + p * 12f
        val a = if (still) 1f else if (p < 0.2f) p / 0.2f else 1f - (p - 0.2f) / 0.8f
        drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), 1.6f * u, Offset((x + p * 3f) * u, y * u))
    }
}

private fun DrawScope.fog(u: Float, t: Float) {
    listOf(Triple(8f, 30f, 14f) to 0f, Triple(11f, 33f, 20f) to 0.35f, Triple(7f, 27f, 26f) to 0.7f).forEach { (l, off) ->
        val dx = sin(frac(t * 2.4f + off) * 2f * PI.toFloat()) * 3f
        drawLine(CloudMid, Offset((l.first + dx) * u, l.third * u), Offset((l.second + dx) * u, l.third * u),
            strokeWidth = 2.6f * u, cap = StrokeCap.Round)
    }
}

private fun DrawScope.lightning(u: Float, t: Float, still: Boolean) {
    // Brief double flash roughly every 3.5 s.
    val p = frac(t * 12f / 3.5f)
    val on = p in 0.86f..0.89f || p in 0.91f..0.94f
    if (!on && !still) return
    val path = Path().apply {
        moveTo(21f * u, 18f * u); lineTo(15f * u, 27f * u); lineTo(19.5f * u, 27f * u)
        lineTo(17f * u, 36f * u); lineTo(26f * u, 23.5f * u); lineTo(21.5f * u, 23.5f * u); lineTo(24f * u, 18f * u); close()
    }
    drawPath(path, Bolt)
}

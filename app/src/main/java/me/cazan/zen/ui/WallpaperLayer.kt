package me.cazan.zen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import me.cazan.zen.data.WallpaperLoader
import me.cazan.zen.data.Wallpaper

val GradientPresets: List<Pair<String, List<Color>>> = listOf(
    "Midnight" to listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)),
    "Aurora" to listOf(Color(0xFF0B1E3D), Color(0xFF0B3D3A), Color(0xFF2A0B3D)),
    "Ocean" to listOf(Color(0xFF000428), Color(0xFF004E92)),
    "Dusk" to listOf(Color(0xFF141E30), Color(0xFF3A1C3F)),
    "Forest" to listOf(Color(0xFF0B1F14), Color(0xFF1D3B2A), Color(0xFF0E2A2A)),
    "Ember" to listOf(Color(0xFF1A1110), Color(0xFF3A1C1C), Color(0xFF4A2A18)),
    "Plum" to listOf(Color(0xFF1A0B2E), Color(0xFF3D1E5C)),
    "Graphite" to listOf(Color(0xFF0C0C0E), Color(0xFF2A2B30)),
)

val SolidPresets: List<Long> = listOf(
    0xFF000000, 0xFF0E1116, 0xFF1B1F27, 0xFF14213D, 0xFF1E2B22, 0xFF2B1B2E, 0xFF2E2A24, 0xFF33363D,
)

fun gradientBrush(preset: Int): Brush =
    Brush.linearGradient(GradientPresets[preset.mod(GradientPresets.size)].second, Offset.Zero, Offset.Infinite)

/** Static background: no animation, no live wallpaper service, zero per-frame cost. */
@Composable
fun WallpaperLayer(wallpaper: Wallpaper, modifier: Modifier = Modifier) {
    when (wallpaper) {
        is Wallpaper.Solid -> Box(modifier.fillMaxSize().background(Color(wallpaper.argb)))
        is Wallpaper.Gradient -> Box(modifier.fillMaxSize().background(gradientBrush(wallpaper.preset)))
        is Wallpaper.Image -> ImageWallpaper(wallpaper.source, modifier)
    }
}

private val Scrim = Brush.verticalGradient(listOf(Color(0xA6000000), Color(0x40000000), Color(0x99000000)))

@Composable
private fun ImageWallpaper(source: String, modifier: Modifier) {
    val context = LocalContext.current
    var image by remember(source) { mutableStateOf(WallpaperLoader.peek(source)) }
    LaunchedEffect(source) {
        if (image == null) {
            val dm = context.resources.displayMetrics
            image = WallpaperLoader.load(context, source, dm.widthPixels, dm.heightPixels)
        }
    }
    Box(modifier.fillMaxSize().background(Zen.Background)) {
        image?.let { Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        // Keeps the clock and labels legible over any photo.
        Box(Modifier.fillMaxSize().background(Scrim))
    }
}

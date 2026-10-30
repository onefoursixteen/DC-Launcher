package me.cazan.zen.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Decodes the image wallpaper at screen resolution (never larger) in RGB_565, so a 4K photo
 * costs ~4 MB of RAM on a 1080p TV instead of ~33 MB. Only the current wallpaper is kept.
 */
object WallpaperLoader {
    private const val MAX_DOWNLOAD = 25L * 1024 * 1024

    @Volatile private var cached: Pair<String, ImageBitmap>? = null

    fun peek(source: String): ImageBitmap? = cached?.takeIf { it.first == source }?.second

    fun clear() {
        cached = null
    }

    suspend fun load(context: Context, source: String, width: Int, height: Int): ImageBitmap? {
        peek(source)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = if (source.startsWith("http://") || source.startsWith("https://")) {
                    downloaded(context, source)
                } else {
                    File(source)
                }
                val bitmap = decodeSampled({ file.inputStream() }, width, height)
                    ?: error("Not an image")
                bitmap.prepareToDraw()
                bitmap.asImageBitmap().also { cached = source to it }
            }.getOrNull()
        }
    }

    /** URL images are downloaded once and reused from the cache dir on later boots. */
    private fun downloaded(context: Context, url: String): File {
        val dir = File(context.cacheDir, "wallpapers").apply { mkdirs() }
        val file = File(dir, "url_" + Integer.toHexString(url.hashCode()))
        if (!file.exists()) {
            dir.listFiles()?.forEach { it.delete() } // keep only the current one
            httpDownload(url, file, MAX_DOWNLOAD)
        }
        return file
    }

    private fun decodeSampled(open: () -> InputStream, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
    }
}

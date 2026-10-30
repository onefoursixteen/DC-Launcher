package me.cazan.zen.data

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class AppEntry(
    /** "package/activityClass", stable across launches; used as the key everywhere. */
    val key: String,
    val label: String,
    val packageName: String,
    val className: String,
    /** True when found via LEANBACK_LAUNCHER (a real TV app), false for phone-style sideloads. */
    val leanback: Boolean,
    val system: Boolean,
)

class AppArt(val bitmap: ImageBitmap, val isBanner: Boolean, val byteCount: Int)

/**
 * Lists launchable apps and loads their banners/icons lazily, off the main thread,
 * into a bounded LRU cache. Nothing is loaded until a tile is actually composed.
 */
class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val state = MutableStateFlow<List<AppEntry>>(emptyList())
    private val activities = ConcurrentHashMap<String, ActivityInfo>()
    private val refreshLock = Mutex()

    private val artCache = object : LruCache<String, AppArt>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: AppArt) = value.byteCount
    }

    val apps: StateFlow<List<AppEntry>> = state.asStateFlow()

    suspend fun refresh() = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val seenPackages = HashSet<String>()
            val result = ArrayList<AppEntry>()

            fun collect(category: String, leanback: Boolean) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
                @Suppress("DEPRECATION")
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    val ai = ri.activityInfo ?: continue
                    if (ai.packageName == context.packageName) continue
                    // One tile per package; the TV (leanback) entry point wins over the phone one.
                    if (!seenPackages.add(ai.packageName)) continue
                    val key = "${ai.packageName}/${ai.name}"
                    activities[key] = ai
                    result += AppEntry(
                        key = key,
                        label = ri.loadLabel(pm).toString().trim(),
                        packageName = ai.packageName,
                        className = ai.name,
                        leanback = leanback,
                        system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
            }

            collect(Intent.CATEGORY_LEANBACK_LAUNCHER, leanback = true)
            collect(Intent.CATEGORY_LAUNCHER, leanback = false)
            result.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            state.value = result
        }
    }

    fun cachedArt(key: String): AppArt? = artCache.get(key)

    suspend fun art(entry: AppEntry): AppArt? {
        artCache.get(entry.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val ai = activities[entry.key] ?: return@withContext null
            runCatching {
                // ActivityInfo.loadBanner falls back to the application banner.
                val banner = ai.loadBanner(pm)
                val art = if (banner != null) {
                    banner.render(BANNER_W, BANNER_H).toArt(isBanner = true)
                } else {
                    ai.loadIcon(pm).render(ICON_SIZE, ICON_SIZE).toArt(isBanner = false)
                }
                artCache.put(entry.key, art)
                art
            }.getOrNull()
        }
    }

    fun launchIntent(entry: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(if (entry.leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun fallbackLaunchIntent(entry: AppEntry): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(entry.packageName) ?: pm.getLaunchIntentForPackage(entry.packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Drops cached art for a package that was updated or removed. */
    fun invalidate(packageName: String) {
        val prefix = "$packageName/"
        artCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { artCache.remove(it) }
    }

    @Suppress("DEPRECATION")
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> artCache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                artCache.trimToSize(artCache.maxSize() / 2)
        }
    }

    private fun Bitmap.toArt(isBanner: Boolean): AppArt {
        prepareToDraw() // start the GPU upload now, not on the first focused frame
        return AppArt(asImageBitmap(), isBanner, allocationByteCount)
    }

    private companion object {
        // Banners are specified as 320x180 dp-equivalent; this is plenty for a 1080p grid tile.
        const val BANNER_W = 320
        const val BANNER_H = 180
        const val ICON_SIZE = 160

        fun cacheSizeBytes(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 8).coerceAtMost(32L * 1024 * 1024).toInt()
        }
    }
}

private fun Drawable.render(width: Int, height: Int): Bitmap {
    if (this is BitmapDrawable) {
        val src = bitmap
        if (src != null && src.width == width && src.height == height) return src
    }
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val oldBounds = copyBounds()
    setBounds(0, 0, width, height)
    draw(Canvas(bmp))
    bounds = oldBounds
    return bmp
}

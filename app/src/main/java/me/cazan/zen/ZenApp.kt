package me.cazan.zen

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import me.cazan.zen.data.AdminServer
import me.cazan.zen.data.AppRepository
import me.cazan.zen.data.LauncherPrefs
import me.cazan.zen.data.NetworkMonitor
import me.cazan.zen.data.SpotlightRepository
import me.cazan.zen.data.WallpaperLoader
import me.cazan.zen.data.WatchlistRepository
import me.cazan.zen.data.WeatherRepository

/** Process-wide singletons. No DI framework: a handful of lazy fields are all this app needs. */
class ZenApp : Application() {
    val prefs by lazy { LauncherPrefs(this) }
    val apps by lazy { AppRepository(this) }
    val weather by lazy { WeatherRepository() }
    val network by lazy { NetworkMonitor(this) }
    val spotlight by lazy { SpotlightRepository(this) }
    val watchlist by lazy { WatchlistRepository(this, spotlight) }
    val admin by lazy { AdminServer(this, prefs, spotlight, watchlist, apps) }

    @Suppress("DEPRECATION") // trim levels are still delivered on the older TVs this targets
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        apps.trimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            WallpaperLoader.clear()
            spotlight.trimMemory()
        }
    }
}

val Context.zen: ZenApp get() = applicationContext as ZenApp

/**
 * Weather cities saved before regions existed have no country code; look it up once.
 * The country doubles as the Spotlight region.
 */
suspend fun ZenApp.ensureRegion() {
    val place = prefs.config.value.place ?: return
    if (place.countryCode != null) return
    val found = weather.geocode(place.name) ?: return
    prefs.update { c ->
        c.copy(place = c.place?.takeIf { it.name == place.name }
            ?.copy(countryCode = found.countryCode, country = found.country) ?: c.place)
    }
}

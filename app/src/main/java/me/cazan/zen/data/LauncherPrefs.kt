package me.cazan.zen.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Category(val id: String, val name: String, val apps: List<String>)

sealed interface Wallpaper {
    data class Solid(val argb: Long) : Wallpaper
    data class Gradient(val preset: Int) : Wallpaper
    /** An https URL, or an absolute path to a local file. */
    data class Image(val source: String) : Wallpaper
}

/** A movie or series the user wants to watch. Kept only on the TV. */
data class WatchItem(
    /** "movie" or "tv" */
    val type: String,
    val id: Int,
    val title: String,
    val year: String,
    val backdrop: String?,
    val overview: String,
    val addedAt: Long,
) {
    val key: String get() = "$type:$id"
}

enum class ClockFormat(val label: String) { SYSTEM("Follow system"), H12("12-hour"), H24("24-hour") }

data class WeatherPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** ISO 3166-1 alpha-2, e.g. "RO". Also used as the Spotlight region. */
    val countryCode: String? = null,
    val country: String? = null,
)

data class Config(
    val hidden: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    /** Global app order (component keys). Apps not listed fall back to alphabetical, after these. */
    val order: List<String> = emptyList(),
    val categories: List<Category> = emptyList(),
    val wallpaper: Wallpaper = Wallpaper.Gradient(0),
    val columns: Int = 5,
    val showLabels: Boolean = true,
    val clock: ClockFormat = ClockFormat.SYSTEM,
    val showWeather: Boolean = false,
    val place: WeatherPlace? = null,
    val fahrenheit: Boolean = false,
    val animateWeather: Boolean = true,
    /** Package opened by the remote's Assistant button (see AssistActivity), or null. */
    val assistApp: String? = null,
    val spotlight: Boolean = false,
    /** TMDB v3 API key or v4 read-access token; set via the web admin page. */
    val tmdbKey: String? = null,
    /** Spotlight shows only titles streaming on an installed app. */
    val onlyMine: Boolean = false,
    /** "type:id" keys the user dismissed from the spotlight. */
    val notInterested: Set<String> = emptySet(),
    val watchlist: List<WatchItem> = emptyList(),
)

const val MIN_COLUMNS = 3
const val MAX_COLUMNS = 8

/**
 * All launcher settings, backed by a single SharedPreferences file and exposed as one
 * StateFlow. SharedPreferences is read synchronously once at process start (a few KB),
 * which is faster on cold start than DataStore and needs no extra dependency.
 */
class LauncherPrefs(context: Context) {
    private val sp = context.getSharedPreferences("zen_config", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val config: StateFlow<Config> = state.asStateFlow()

    @Synchronized
    fun update(transform: (Config) -> Config) {
        val old = state.value
        val new = transform(old)
        if (new == old) return
        state.value = new
        save(new)
    }

    fun toggleFavorite(key: String) = update { it.copy(favorites = it.favorites.toggle(key)) }

    fun setHidden(key: String, hidden: Boolean) = update {
        if (hidden) it.copy(hidden = it.hidden + key, favorites = it.favorites - key)
        else it.copy(hidden = it.hidden - key)
    }

    /** Moves [key] into the slot currently held by [target], within the full display order. */
    fun moveApp(key: String, target: String, displayOrder: List<String>) = update { c ->
        val list = displayOrder.toMutableList()
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        if (from < 0 || to < 0 || from == to) return@update c
        list.removeAt(from)
        list.add(to, key)
        c.copy(order = list)
    }

    fun resetOrder() = update { it.copy(order = emptyList()) }

    fun addCategory(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        update { it.copy(categories = it.categories + Category(UUID.randomUUID().toString().take(8), clean, emptyList())) }
    }

    fun renameCategory(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        update { c -> c.copy(categories = c.categories.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    fun deleteCategory(id: String) = update { c -> c.copy(categories = c.categories.filterNot { it.id == id }) }

    fun moveCategoryUp(id: String) = update { c ->
        val i = c.categories.indexOfFirst { it.id == id }
        if (i <= 0) return@update c
        val list = c.categories.toMutableList()
        list.add(i - 1, list.removeAt(i))
        c.copy(categories = list)
    }

    fun toggleInCategory(id: String, key: String) = update { c ->
        c.copy(categories = c.categories.map { cat ->
            if (cat.id != id) cat
            else cat.copy(apps = if (key in cat.apps) cat.apps - key else cat.apps + key)
        })
    }

    // ---- persistence ----

    private fun load(): Config = Config(
        hidden = sp.getStringSet(K_HIDDEN, null)?.toSet() ?: emptySet(),
        favorites = sp.getStringSet(K_FAVORITES, null)?.toSet() ?: emptySet(),
        order = sp.getString(K_ORDER, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList(),
        categories = parseCategories(sp.getString(K_CATEGORIES, null)),
        wallpaper = parseWallpaper(sp.getString(K_WALLPAPER, null)),
        columns = sp.getInt(K_COLUMNS, 5).coerceIn(MIN_COLUMNS, MAX_COLUMNS),
        showLabels = sp.getBoolean(K_LABELS, true),
        clock = ClockFormat.entries.firstOrNull { it.name == sp.getString(K_CLOCK, null) } ?: ClockFormat.SYSTEM,
        showWeather = sp.getBoolean(K_WEATHER, false),
        place = sp.getString(K_PLACE, null)?.let(::parsePlace),
        fahrenheit = sp.getBoolean(K_FAHRENHEIT, false),
        animateWeather = sp.getBoolean(K_ANIMATE_WEATHER, true),
        assistApp = sp.getString(K_ASSIST, null),
        spotlight = sp.getBoolean(K_SPOTLIGHT, false),
        tmdbKey = sp.getString(K_TMDB, null),
        onlyMine = sp.getBoolean(K_ONLY_MINE, false),
        notInterested = sp.getStringSet(K_NOT_INTERESTED, null)?.toSet() ?: emptySet(),
        watchlist = parseWatchlist(sp.getString(K_WATCHLIST, null)),
    )

    private fun save(c: Config) {
        sp.edit()
            .putStringSet(K_HIDDEN, HashSet(c.hidden))
            .putStringSet(K_FAVORITES, HashSet(c.favorites))
            .putString(K_ORDER, c.order.joinToString("\n"))
            .putString(K_CATEGORIES, JSONArray().apply {
                c.categories.forEach { cat ->
                    put(JSONObject().put("id", cat.id).put("name", cat.name).put("apps", JSONArray(cat.apps)))
                }
            }.toString())
            .putString(K_WALLPAPER, when (val w = c.wallpaper) {
                is Wallpaper.Solid -> "solid:" + java.lang.Long.toHexString(w.argb)
                is Wallpaper.Gradient -> "gradient:${w.preset}"
                is Wallpaper.Image -> "image:${w.source}"
            })
            .putInt(K_COLUMNS, c.columns)
            .putBoolean(K_LABELS, c.showLabels)
            .putString(K_CLOCK, c.clock.name)
            .putBoolean(K_WEATHER, c.showWeather)
            .putString(K_PLACE, c.place?.let {
                JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)
                    .putOpt("cc", it.countryCode).putOpt("country", it.country).toString()
            })
            .putBoolean(K_FAHRENHEIT, c.fahrenheit)
            .putBoolean(K_ANIMATE_WEATHER, c.animateWeather)
            .putString(K_ASSIST, c.assistApp)
            .putBoolean(K_SPOTLIGHT, c.spotlight)
            .putString(K_TMDB, c.tmdbKey)
            .putBoolean(K_ONLY_MINE, c.onlyMine)
            .putStringSet(K_NOT_INTERESTED, HashSet(c.notInterested))
            .putString(K_WATCHLIST, JSONArray().apply {
                c.watchlist.forEach { w ->
                    put(JSONObject().put("type", w.type).put("id", w.id).put("title", w.title).put("year", w.year)
                        .putOpt("backdrop", w.backdrop).put("overview", w.overview).put("added", w.addedAt))
                }
            }.toString())
            .apply()
    }

    private fun parseCategories(raw: String?): List<Category> = runCatching {
        val arr = JSONArray(raw ?: return emptyList())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val apps = o.getJSONArray("apps")
            Category(o.getString("id"), o.getString("name"), List(apps.length()) { apps.getString(it) })
        }
    }.getOrDefault(emptyList())

    private fun parseWatchlist(raw: String?): List<WatchItem> = runCatching {
        val arr = JSONArray(raw ?: return emptyList())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            WatchItem(
                o.getString("type"), o.getInt("id"), o.getString("title"), o.optString("year"),
                o.optString("backdrop").ifEmpty { null }, o.optString("overview"), o.optLong("added"),
            )
        }
    }.getOrDefault(emptyList())

    fun addToWatchlist(item: WatchItem) = update { c ->
        if (c.watchlist.any { it.key == item.key }) c
        else c.copy(watchlist = listOf(item) + c.watchlist, notInterested = c.notInterested - item.key)
    }

    fun removeFromWatchlist(key: String) = update { c -> c.copy(watchlist = c.watchlist.filterNot { it.key == key }) }

    fun notInterested(key: String) = update { c -> c.copy(notInterested = c.notInterested + key) }

    private fun parseWallpaper(raw: String?): Wallpaper {
        val value = raw?.substringAfter(':', "") ?: return Wallpaper.Gradient(0)
        return when (raw.substringBefore(':')) {
            "solid" -> value.toLongOrNull(16)?.let { Wallpaper.Solid(it) }
            "gradient" -> value.toIntOrNull()?.let { Wallpaper.Gradient(it) }
            "image" -> value.takeIf { it.isNotEmpty() }?.let { Wallpaper.Image(it) }
            else -> null
        } ?: Wallpaper.Gradient(0)
    }

    private fun parsePlace(raw: String): WeatherPlace? = runCatching {
        val o = JSONObject(raw)
        WeatherPlace(
            o.getString("name"), o.getDouble("lat"), o.getDouble("lon"),
            o.optString("cc").ifEmpty { null }, o.optString("country").ifEmpty { null },
        )
    }.getOrNull()

    private companion object {
        const val K_HIDDEN = "hidden"
        const val K_FAVORITES = "favorites"
        const val K_ORDER = "order"
        const val K_CATEGORIES = "categories"
        const val K_WALLPAPER = "wallpaper"
        const val K_COLUMNS = "columns"
        const val K_LABELS = "labels"
        const val K_CLOCK = "clock"
        const val K_WEATHER = "weather"
        const val K_PLACE = "place"
        const val K_FAHRENHEIT = "fahrenheit"
        const val K_ANIMATE_WEATHER = "animate_weather"
        const val K_ASSIST = "assist_app"
        const val K_SPOTLIGHT = "spotlight"
        const val K_TMDB = "tmdb_key"
        const val K_ONLY_MINE = "only_mine"
        const val K_NOT_INTERESTED = "not_interested"
        const val K_WATCHLIST = "watchlist"
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

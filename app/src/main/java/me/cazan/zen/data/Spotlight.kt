package me.cazan.zen.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class SpotlightItem(
    val id: Int,
    /** "movie" or "tv" */
    val type: String,
    val title: String,
    val overview: String,
    val year: String,
    val genres: List<String>,
    val rating: Double,
    val backdrop: String,
    val logo: String?,
    /** Subscription services in the user's region, e.g. ["Netflix"]. */
    val providers: List<String>,
    /** Why it's here: "New this week", "New series this week", "New season 3 this week", "From your watchlist". */
    val reason: String,
) {
    val key: String get() = "$type:$id"
}

/**
 * "New this week" from TMDB: movies released and series with episodes airing this week,
 * in the region of the user's weather city. Fetched at most every 12 hours, only while the
 * home screen is visible, and cached on disk so a reboot shows it instantly and offline.
 * Only one or two backdrops are kept in memory at a time.
 */
class SpotlightRepository(private val context: Context) {
    private val state = MutableStateFlow<List<SpotlightItem>>(emptyList())
    private val statusState = MutableStateFlow("")
    private val lock = Mutex()
    private val cacheFile = File(context.filesDir, "spotlight.json")
    private val imageDir = File(context.cacheDir, "spotlight")
    private var fetchedAt = 0L
    private var fetchedFor = ""
    private var loadedFromDisk = false

    private val bitmaps = object : LruCache<String, ImageBitmap>(14 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }

    val items: StateFlow<List<SpotlightItem>> = state.asStateFlow()
    val status: StateFlow<String> = statusState.asStateFlow()

    suspend fun refreshIfStale(key: String, region: String?, force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!loadedFromDisk) loadFromDisk()
            val wanted = "v3|$region|${weekStart()}|${key.hashCode()}"
            val fresh = System.currentTimeMillis() - fetchedAt < MAX_AGE_MS && fetchedFor == wanted
            if (fresh && !force && state.value.isNotEmpty()) return@withContext
            statusState.value = "Updating…"
            runCatching { fetch(key, region) }
                .onSuccess { list ->
                    state.value = list
                    fetchedAt = System.currentTimeMillis()
                    fetchedFor = wanted
                    saveToDisk()
                    statusState.value = "${list.size} titles · updated just now"
                }
                .onFailure { e ->
                    statusState.value = when ((e as? HttpStatusException)?.code) {
                        401 -> "TMDB rejected the API key"
                        else -> "Couldn't reach TMDB (${e.message ?: "offline"})"
                    }
                }
        }
    }

    fun clear() {
        state.value = emptyList()
        fetchedAt = 0
        cacheFile.delete()
        imageDir.deleteRecursively()
        bitmaps.evictAll()
        statusState.value = ""
    }

    suspend fun backdrop(item: SpotlightItem): ImageBitmap? = image(item.backdrop, "w1280", alpha = false)

    suspend fun logo(item: SpotlightItem): ImageBitmap? = item.logo?.let { image(it, "w500", alpha = true) }

    fun cachedBackdrop(item: SpotlightItem): ImageBitmap? = bitmaps.get("w1280${item.backdrop}")

    fun cachedLogo(item: SpotlightItem): ImageBitmap? = item.logo?.let { bitmaps.get("w500$it") }

    fun trimMemory() = bitmaps.evictAll()

    // ---------------------------------------------------------------- fetching

    /**
     * Only genuinely new titles: movies first released in the last ~2 months that open in the
     * region this week, series whose first episode airs this week, and season premieres
     * (episode 1) airing this week. Long-running shows with a regular weekly episode and older
     * films getting a re-release are deliberately left out. No "trending" filler.
     */
    private fun fetch(key: String, region: String?): List<SpotlightItem> {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val (from, to) = weekStart() to weekEnd()
        val recent = day(-NEW_MOVIE_DAYS)
        val regionMovie = region?.let { "&region=$it&with_release_type=2%7C3%7C4%7C6" }.orEmpty()
        val base = "language=$lang&sort_by=popularity.desc&include_adult=false"

        val movies = results(key, "/discover/movie?$base&release_date.gte=$from&release_date.lte=$to" +
            "&primary_release_date.gte=$recent&primary_release_date.lte=$to$regionMovie")
        val newSeries = results(key, "/discover/tv?$base&first_air_date.gte=$from&first_air_date.lte=$to")
        val newIds = newSeries.mapTo(HashSet()) { it.getInt("id") }
        // Shows with any episode this week; only their season premieres survive the details check.
        val seasonCandidates = (1..2).flatMap { page ->
            results(key, "/discover/tv?$base&air_date.gte=$from&air_date.lte=$to&page=$page")
        }.filter { it.getInt("id") !in newIds }.distinctBy { it.getInt("id") }.take(SEASON_CANDIDATES)

        fun detailsOf(list: List<JSONObject>, type: String, limit: Int) = list.asSequence()
            .mapNotNull { runCatching { details(key, it, type, lang, region, from, to, recent) }.getOrNull() }
            .take(limit).toList()

        // Titles you can actually stream in your region come first; popularity decides the rest.
        fun rank(items: List<SpotlightItem>) = items.sortedByDescending { it.providers.isNotEmpty() }.take(PER_TYPE)

        val movieItems = rank(detailsOf(movies, "movie", PER_TYPE * 2))
        val tvCandidates = (newSeries + seasonCandidates).sortedByDescending { it.optDouble("popularity") }
        val tvItems = rank(detailsOf(tvCandidates, "tv", PER_TYPE * 2))
        return interleave(movieItems, tvItems).take(MAX_ITEMS)
    }

    private fun results(key: String, path: String): List<JSONObject> {
        val arr = tmdb(key, path).optJSONArray("results") ?: return emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }
            .filter { it.optString("backdrop_path").isNotEmpty() && !it.optBoolean("adult") }
    }

    /** Returns null when the title turns out not to be new this week. */
    private fun details(
        key: String, summary: JSONObject, type: String, lang: String, region: String?,
        from: String, to: String, recent: String,
    ): SpotlightItem? {
        val id = summary.getInt("id")
        val d = tmdb(key, "/$type/$id?language=$lang&append_to_response=images,watch/providers" +
            "&include_image_language=$lang,en,null")

        val logos = d.optJSONObject("images")?.optJSONArray("logos")
        val logo = logos?.let { arr ->
            // BitmapFactory can't decode SVG logos, so only consider PNGs.
            val all = List(arr.length()) { arr.getJSONObject(it) }.filter { it.optString("file_path").endsWith(".png") }
            (all.firstOrNull { it.optString("iso_639_1") == lang } ?: all.firstOrNull { it.optString("iso_639_1") == "en" }
                ?: all.firstOrNull())?.optString("file_path")
        }
        val providers = region?.let { r ->
            d.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(r)
                ?.optJSONArray("flatrate")?.let { arr -> List(arr.length()) { arr.getJSONObject(it).getString("provider_name") } }
        }.orEmpty()
        val genres = d.optJSONArray("genres")?.let { arr -> List(arr.length()) { arr.getJSONObject(it).getString("name") } }.orEmpty()
        val date = d.optString(if (type == "movie") "release_date" else "first_air_date")

        fun inWeek(d: String?) = d != null && d.length >= 10 && d.take(10) in from..to
        fun premiere(ep: JSONObject?) = ep != null && ep.optInt("episode_number") == 1 && inWeek(ep.optString("air_date"))
        val reason = when (type) {
            "movie" -> if (date.isNotEmpty() && date >= recent) "New this week" else return null
            else -> {
                val last = d.optJSONObject("last_episode_to_air")
                val next = d.optJSONObject("next_episode_to_air")
                when {
                    inWeek(date) -> "New series this week"
                    premiere(last) -> "New season ${last!!.optInt("season_number")} this week"
                    premiere(next) -> "New season ${next!!.optInt("season_number")} this week"
                    else -> return null
                }
            }
        }
        return SpotlightItem(
            id = id,
            type = type,
            title = d.optString(if (type == "movie") "title" else "name"),
            overview = d.optString("overview"),
            year = date.take(4),
            genres = genres.take(3),
            rating = d.optDouble("vote_average", 0.0),
            backdrop = d.optString("backdrop_path").ifEmpty { summary.getString("backdrop_path") },
            logo = logo?.ifEmpty { null },
            providers = providers,
            reason = reason,
        )
    }

    private fun tmdb(key: String, path: String): JSONObject {
        val k = key.trim()
        // v4 "API Read Access Tokens" are JWTs; v3 keys are 32 hex chars.
        return if (k.startsWith("eyJ")) {
            JSONObject(httpGetText(API + path, mapOf("Authorization" to "Bearer $k", "Accept" to "application/json")))
        } else {
            val sep = if ('?' in path) '&' else '?'
            JSONObject(httpGetText("$API$path${sep}api_key=$k"))
        }
    }

    /** Returns null when the key works, otherwise a human-readable reason. */
    suspend fun validateKey(key: String): String? = withContext(Dispatchers.IO) {
        try {
            tmdb(key, "/configuration")
            null
        } catch (e: HttpStatusException) {
            if (e.code == 401) "TMDB rejected this key" else "TMDB returned HTTP ${e.code}"
        } catch (e: Exception) {
            "Couldn't reach TMDB (${e.message ?: "offline"})"
        }
    }

    // ---------------------------------------------------------------- images

    /** Smaller artwork for watchlist tiles. */
    suspend fun thumb(path: String): ImageBitmap? = image(path, "w780", alpha = false)

    fun cachedThumb(path: String): ImageBitmap? = bitmaps.get("w780$path")

    internal fun tmdbJson(key: String, path: String): JSONObject = tmdb(key, path)

    private suspend fun image(path: String, size: String, alpha: Boolean): ImageBitmap? {
        val cacheKey = size + path
        bitmaps.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                imageDir.mkdirs()
                val file = File(imageDir, size + path.replace('/', '_'))
                if (!file.exists()) httpDownload("https://image.tmdb.org/t/p/$size$path", file, 5L * 1024 * 1024)
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = if (alpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.also { it.prepareToDraw() }?.asImageBitmap()
            }.getOrNull()?.also { bitmaps.put(cacheKey, it) }
        }
    }

    // ---------------------------------------------------------------- disk cache

    private fun saveToDisk() {
        val arr = JSONArray()
        state.value.forEach { i ->
            arr.put(JSONObject()
                .put("id", i.id).put("type", i.type).put("title", i.title).put("overview", i.overview)
                .put("year", i.year).put("genres", JSONArray(i.genres)).put("rating", i.rating)
                .put("backdrop", i.backdrop).putOpt("logo", i.logo)
                .put("providers", JSONArray(i.providers)).put("reason", i.reason))
        }
        runCatching {
            cacheFile.writeText(JSONObject().put("at", fetchedAt).put("for", fetchedFor).put("items", arr).toString())
        }
        // Drop spotlight images that belong to titles no longer shown (watchlist art is kept).
        val keep = state.value.flatMap { listOfNotNull(it.backdrop, it.logo) }.map { it.replace('/', '_') }
        imageDir.listFiles()?.filter { f -> !f.name.startsWith("w780") && keep.none { f.name.endsWith(it) } }?.forEach { it.delete() }
    }

    private fun loadFromDisk() {
        loadedFromDisk = true
        runCatching {
            val o = JSONObject(cacheFile.readText())
            fetchedAt = o.getLong("at")
            fetchedFor = o.getString("for")
            val arr = o.getJSONArray("items")
            state.value = List(arr.length()) { idx ->
                val j = arr.getJSONObject(idx)
                fun strings(name: String) = j.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
                SpotlightItem(
                    j.getInt("id"), j.getString("type"), j.getString("title"), j.getString("overview"),
                    j.getString("year"), strings("genres"), j.getDouble("rating"), j.getString("backdrop"),
                    j.optString("logo").ifEmpty { null }, strings("providers"), j.getString("reason"),
                )
            }
        }
    }

    private companion object {
        const val API = "https://api.themoviedb.org/3"
        const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
        const val PER_TYPE = 6
        const val MAX_ITEMS = 12
        const val NEW_MOVIE_DAYS = 60
        const val SEASON_CANDIDATES = 30

        fun <T> interleave(a: List<T>, b: List<T>): List<T> =
            (0 until maxOf(a.size, b.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it)) }

        private fun week(offsetDays: Int): String {
            val c = Calendar.getInstance().apply {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                add(Calendar.DAY_OF_YEAR, offsetDays)
            }
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
        }

        fun weekStart() = week(0)
        fun weekEnd() = week(6)

        fun day(offsetFromToday: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offsetFromToday) }.time)
    }
}

/**
 * Maps a TMDB provider name to the Android TV packages that play it. Falls back to matching
 * an installed app's label, so regional services work without being listed here.
 */
fun providerApps(providers: List<String>, installed: List<AppEntry>): List<Pair<String, AppEntry>> {
    val byPackage = installed.associateBy { it.packageName }
    return providers.mapNotNull { name ->
        val n = name.lowercase(Locale.ROOT)
        val packages = when {
            "netflix" in n -> listOf("com.netflix.ninja")
            "prime video" in n || "amazon" in n -> listOf("com.amazon.amazonvideo.livingroom")
            n == "max" || "hbo" in n -> listOf("com.wbd.stream", "com.hbo.hbonow")
            "disney" in n -> listOf("com.disney.disneyplus")
            "apple tv" in n -> listOf("com.apple.atve.androidtv.appletv")
            "skyshowtime" in n -> listOf("com.skyshowtime.skyshowtime.google")
            "paramount" in n -> listOf("com.cbs.ott")
            "voyo" in n -> listOf("net.cme.voyo.ro.tvapp")
            "youtube" in n -> listOf("com.google.android.youtube.tv")
            "plex" in n -> listOf("com.plexapp.android")
            "mubi" in n -> listOf("com.mubi")
            else -> emptyList()
        }
        val app = packages.firstNotNullOfOrNull { byPackage[it] }
            ?: installed.firstOrNull { it.label.equals(name, ignoreCase = true) }
        app?.let { name to it }
    }.distinctBy { it.second.packageName }
}

package me.cazan.zen.data

import android.content.Context
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What TMDB said about a watchlist title at the last daily check. Dates are "yyyy-MM-dd". */
data class WatchStatus(
    val providers: List<String>,
    val releaseDate: String?,
    val nextEpisodeDate: String?,
    val lastEpisodeDate: String?,
    val ended: Boolean,
    val logo: String?,
    val genres: List<String>,
    val rating: Double,
    /** When it was first seen on any subscription service; drives "just arrived" promotion. */
    val firstAvailableAt: Long?,
    val checkedAt: Long,
    val region: String?,
    /** e.g. "S2E6"; null when TMDB has no scheduled/aired episode. */
    val nextEpisodeTag: String? = null,
    val nextEpisodeName: String? = null,
    val lastEpisodeTag: String? = null,
    /** Regional digital (VOD) release date for movies, if announced. */
    val digitalDate: String? = null,
)

data class SearchResult(val type: String, val id: Int, val title: String, val year: String, val poster: String?)

/**
 * Daily availability checks for the watchlist: one small TMDB request per title, only while
 * the home screen is visible. Results are cached on disk so badges survive reboots.
 */
class WatchlistRepository(context: Context, private val tmdb: SpotlightRepository) {
    private val file = File(context.filesDir, "watch_status.json")
    private val state = MutableStateFlow(load())
    private val lock = Mutex()

    val status: StateFlow<Map<String, WatchStatus>> = state.asStateFlow()

    suspend fun refresh(key: String, region: String?, items: List<WatchItem>, force: Boolean = false) = lock.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val current = state.value
            val next = current.filterKeys { k -> items.any { it.key == k } }.toMutableMap()
            for (item in items) {
                val old = current[item.key]
                val fresh = old != null && old.region == region && now - old.checkedAt < MAX_AGE_MS
                if (fresh && !force) continue
                runCatching { fetch(key, region, item, old, now) }.onSuccess { next[item.key] = it }
            }
            if (next != current) {
                state.value = next
                save(next)
            }
        }
    }

    private fun fetch(key: String, region: String?, item: WatchItem, old: WatchStatus?, now: Long): WatchStatus {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val extra = if (item.type == "movie") ",release_dates" else ""
        val d = tmdb.tmdbJson(key, "/${item.type}/${item.id}?language=$lang" +
            "&append_to_response=watch/providers,images$extra&include_image_language=$lang,en,null")

        val providers = region?.let { r ->
            d.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(r)
                ?.optJSONArray("flatrate")?.let { a -> List(a.length()) { a.getJSONObject(it).getString("provider_name") } }
        }.orEmpty()

        // Prefer the region's own dates over the worldwide premiere.
        val regionalDates = region?.let { r ->
            d.optJSONObject("release_dates")?.optJSONArray("results")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("iso_3166_1") == r }
                    ?.optJSONArray("release_dates")?.let { rd -> (0 until rd.length()).map { rd.getJSONObject(it) } }
            }
        }.orEmpty()
        fun earliest(types: IntRange) = regionalDates.filter { it.optInt("type") in types }
            .minOfOrNull { it.optString("release_date").take(10) }?.ifEmpty { null }
        val regional = earliest(2..3)
        val nextEp = d.optJSONObject("next_episode_to_air")
        val lastEp = d.optJSONObject("last_episode_to_air")
        fun tag(ep: JSONObject?) = ep?.let { "S${it.optInt("season_number")}E${it.optInt("episode_number")}" }
        val logo = d.optJSONObject("images")?.optJSONArray("logos")?.let { arr ->
            val pngs = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("file_path").endsWith(".png") }
            (pngs.firstOrNull { it.optString("iso_639_1") == lang } ?: pngs.firstOrNull { it.optString("iso_639_1") == "en" }
                ?: pngs.firstOrNull())?.optString("file_path")
        }
        val genres = d.optJSONArray("genres")?.let { a -> List(a.length()) { a.getJSONObject(it).getString("name") } }.orEmpty()

        return WatchStatus(
            providers = providers,
            releaseDate = regional ?: d.optString(if (item.type == "movie") "release_date" else "first_air_date").ifEmpty { null },
            nextEpisodeDate = nextEp?.optString("air_date")?.ifEmpty { null },
            lastEpisodeDate = lastEp?.optString("air_date")?.ifEmpty { null },
            nextEpisodeTag = tag(nextEp),
            nextEpisodeName = nextEp?.optString("name")?.ifEmpty { null },
            lastEpisodeTag = tag(lastEp),
            digitalDate = earliest(4..4),
            ended = d.optString("status") in setOf("Ended", "Canceled"),
            logo = logo,
            genres = genres.take(3),
            rating = d.optDouble("vote_average", 0.0),
            firstAvailableAt = when {
                providers.isEmpty() -> null
                old?.firstAvailableAt != null && old.providers.isNotEmpty() -> old.firstAvailableAt
                else -> now
            },
            checkedAt = now,
            region = region,
        )
    }

    /** Full details for a title being added from the web page (never trusts form fields). */
    suspend fun lookup(key: String, type: String, id: Int): WatchItem? = withContext(Dispatchers.IO) {
        runCatching {
            require(type == "movie" || type == "tv")
            val lang = Locale.getDefault().language.ifEmpty { "en" }
            val d = tmdb.tmdbJson(key, "/$type/$id?language=$lang")
            WatchItem(
                type = type,
                id = id,
                title = d.optString(if (type == "movie") "title" else "name"),
                year = d.optString(if (type == "movie") "release_date" else "first_air_date").take(4),
                backdrop = d.optString("backdrop_path").ifEmpty { null },
                overview = d.optString("overview"),
                addedAt = System.currentTimeMillis(),
            )
        }.getOrNull()
    }

    suspend fun search(key: String, query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val lang = Locale.getDefault().language.ifEmpty { "en" }
            val q = URLEncoder.encode(query.trim().take(100), "UTF-8")
            val arr = tmdb.tmdbJson(key, "/search/multi?query=$q&include_adult=false&language=$lang").optJSONArray("results")
                ?: return@runCatching emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("media_type") in setOf("movie", "tv") }
                .take(12)
                .map { o ->
                    val movie = o.getString("media_type") == "movie"
                    SearchResult(
                        type = o.getString("media_type"),
                        id = o.getInt("id"),
                        title = o.optString(if (movie) "title" else "name"),
                        year = o.optString(if (movie) "release_date" else "first_air_date").take(4),
                        poster = o.optString("poster_path").ifEmpty { null },
                    )
                }
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- persistence

    private fun save(map: Map<String, WatchStatus>) = runCatching {
        val o = JSONObject()
        map.forEach { (k, s) ->
            o.put(k, JSONObject()
                .put("providers", JSONArray(s.providers)).putOpt("release", s.releaseDate)
                .putOpt("next", s.nextEpisodeDate).putOpt("last", s.lastEpisodeDate).put("ended", s.ended)
                .putOpt("nextTag", s.nextEpisodeTag).putOpt("nextName", s.nextEpisodeName)
                .putOpt("lastTag", s.lastEpisodeTag).putOpt("digital", s.digitalDate).put("v", 2)
                .putOpt("logo", s.logo).put("genres", JSONArray(s.genres)).put("rating", s.rating)
                .putOpt("firstAvail", s.firstAvailableAt).put("checked", s.checkedAt).putOpt("region", s.region))
        }
        file.writeText(o.toString())
    }

    private fun load(): Map<String, WatchStatus> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val j = o.getJSONObject(k)
            fun strings(n: String) = j.getJSONArray(n).let { a -> List(a.length()) { a.getString(it) } }
            fun opt(n: String) = j.optString(n).ifEmpty { null }
            WatchStatus(
                strings("providers"), opt("release"), opt("next"), opt("last"), j.optBoolean("ended"),
                opt("logo"), strings("genres"), j.optDouble("rating"),
                if (j.has("firstAvail")) j.getLong("firstAvail") else null,
                // Entries saved before episode details existed are re-checked right away.
                if (j.optInt("v") >= 2) j.getLong("checked") else 0L,
                opt("region"), opt("nextTag"), opt("nextName"), opt("lastTag"), opt("digital"),
            )
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_AGE_MS = 20 * 60 * 60 * 1000L
    }
}

// ---------------------------------------------------------------- labels

/** The badge under a watchlist tile, and whether it's good news worth promoting. */
data class WatchLabel(val text: String, val watchable: Boolean, val promote: Boolean, val app: AppEntry?)

private const val DAY = 24 * 60 * 60 * 1000L

private fun parseDay(s: String?): Long? = s?.let {
    runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(it)?.time }.getOrNull()
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "today", "tomorrow", "Thursday" (or "Thu" when [short]) within a week, otherwise "Oct 30". */
fun friendlyDay(date: String?, short: Boolean = false): String? {
    val t = parseDay(date) ?: return null
    val days = TimeUnit.MILLISECONDS.toDays(t - startOfToday()).toInt()
    return when {
        days == 0 -> "today"
        days == 1 -> "tomorrow"
        days in 2..6 -> SimpleDateFormat(if (short) "EEE" else "EEEE", Locale.getDefault()).format(Date(t))
        days in -364..364 -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t))
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(t))
    }
}

/** "Thursday, October 23" — used in the details panel. */
private fun longDay(date: String?): String? {
    val t = parseDay(date) ?: return null
    val days = TimeUnit.MILLISECONDS.toDays(t - startOfToday()).toInt()
    val pattern = if (days in -364..364) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy"
    val text = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
    return when (days) { 0 -> "today ($text)"; 1 -> "tomorrow ($text)"; else -> text }
}

/** The longer line shown in the details panel, e.g. "Next episode: S2E6 “Title” · Thursday, October 23". */
fun watchDetail(item: WatchItem, s: WatchStatus?): String? {
    if (s == null) return null
    return if (item.type == "tv") {
        val next = longDay(s.nextEpisodeDate)
        when {
            next != null -> "Next episode: " + listOfNotNull(s.nextEpisodeTag, s.nextEpisodeName?.let { "“$it”" }).joinToString(" ") + " · $next"
            s.ended -> "The series has ended" + (s.lastEpisodeTag?.let { "; the last episode was $it" } ?: "")
            else -> "No next episode announced yet" + (s.lastEpisodeTag?.let { " (latest: $it, ${longDay(s.lastEpisodeDate)})" } ?: "")
        }
    } else {
        listOfNotNull(
            s.releaseDate?.let { "Cinema release: ${longDay(it)}" },
            s.digitalDate?.let { "Digital release: ${longDay(it)}" },
        ).joinToString("\n").ifEmpty { null }
    }
}

fun watchLabel(item: WatchItem, s: WatchStatus?, installed: List<AppEntry>, now: Long = System.currentTimeMillis()): WatchLabel {
    if (s == null) return WatchLabel("Checking…", watchable = false, promote = false, app = null)
    val mine = providerApps(s.providers, installed)
    val app = mine.firstOrNull()?.second
    val where = when {
        mine.isNotEmpty() -> mine.first().first
        s.providers.isNotEmpty() -> "${s.providers.first()} (not installed)"
        else -> null
    }
    val today = startOfToday()

    if (item.type == "tv") {
        val next = parseDay(s.nextEpisodeDate)
        val last = parseDay(s.lastEpisodeDate)
        val recent = last != null && last >= today - 6 * DAY && last <= today
        val nextTag = s.nextEpisodeTag ?: "New episode"
        val lastTag = s.lastEpisodeTag ?: "New episode"
        val text = when {
            next != null && next == today -> "$nextTag out today"
            // The upcoming episode leads so it survives truncation on narrow tiles.
            recent && next != null -> "$nextTag ${friendlyDay(s.nextEpisodeDate, short = true)} · $lastTag out now"
            recent -> "$lastTag out now"
            next != null -> "$nextTag · ${friendlyDay(s.nextEpisodeDate, short = true)}"
            s.ended -> "Ended"
            else -> "Next season not announced"
        }
        val promote = app != null && ((next != null && next == today) || (last != null && last >= today - 2 * DAY && last <= today))
        return WatchLabel(listOfNotNull(text, where).joinToString(" · "), app != null, promote, app)
    }

    // Movie
    val release = parseDay(s.releaseDate)
    return when {
        app != null -> WatchLabel(
            "Now streaming on ${mine.first().first}", true,
            promote = s.firstAvailableAt != null && now - s.firstAvailableAt < 14 * DAY, app = app,
        )
        s.providers.isNotEmpty() -> WatchLabel("Streaming on $where", false, false, null)
        release != null && release > today -> WatchLabel("In cinemas ${friendlyDay(s.releaseDate)}", false, false, null)
        (parseDay(s.digitalDate) ?: 0) > today -> WatchLabel("Digital release ${friendlyDay(s.digitalDate)}", false, false, null)
        release != null && release > today - 150 * DAY -> WatchLabel("In cinemas, not streaming yet", false, false, null)
        else -> WatchLabel("Not streaming${s.region?.let { " in $it" } ?: ""} yet", false, false, null)
    }
}

fun WatchItem.toSpotlight(s: WatchStatus, reason: String) = SpotlightItem(
    id = id, type = type, title = title, overview = overview, year = year, genres = s.genres,
    rating = s.rating, backdrop = backdrop ?: "", logo = s.logo, providers = s.providers, reason = reason,
)

fun SpotlightItem.toWatchItem() = WatchItem(type, id, title, year, backdrop.ifEmpty { null }, overview, System.currentTimeMillis())

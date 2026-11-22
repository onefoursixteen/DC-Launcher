package me.cazan.zen.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Where a title stands in the user's Seerr / Jellyfin library. */
enum class SeerrState(val label: String) {
    NONE(""),
    PENDING("Requested · waiting for approval"),
    PROCESSING("Requested · downloading"),
    PARTIAL("Partly in your library"),
    AVAILABLE("In your library"),
    DECLINED("Request declined"),
}

data class SeerrSeason(val number: Int, val name: String, val episodes: Int, val state: SeerrState)

data class SeerrTitle(
    val type: String,
    val id: Int,
    val state: SeerrState,
    /** Series only, without specials. */
    val seasons: List<SeerrSeason> = emptyList(),
)

data class SeerrResult(
    val type: String,
    val id: Int,
    val title: String,
    val year: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val state: SeerrState,
) {
    val key: String get() = "$type:$id"
}

/** Thrown when Seerr no longer accepts the saved session (signed out, or it expired). */
class SeerrSignedOut : IOException("Seerr session expired")

/**
 * Seerr (Jellyseerr/Overseerr) on the home network, signed in as the user's Jellyfin account.
 * Only the Seerr session cookie is kept on the TV, never the password; requests are made as
 * that user, so their quotas and auto-approve rules apply.
 */
class SeerrClient(private val prefs: LauncherPrefs) {
    private val titles = ConcurrentHashMap<String, Pair<Long, SeerrTitle>>()
    private val signedOutState = MutableStateFlow(false)

    /** True after Seerr rejected the saved session; Web Admin offers to sign in again. */
    val signedOut: StateFlow<Boolean> = signedOutState.asStateFlow()

    val connected: Boolean get() = prefs.config.value.seerr != null

    // ---------------------------------------------------------------- sign-in

    /** Signs in with Jellyfin credentials and saves the session. Returns null, or why it failed. */
    suspend fun signIn(rawUrl: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        val base = normalise(rawUrl) ?: return@withContext "Use an address like http://192.168.0.20:5055 (home network) or an https:// address."
        try {
            val publicSettings = JSONObject(call(base, null, "GET", "/api/v1/settings/public", null).body)
            if (publicSettings.optInt("mediaServerType", 0) !in setOf(0, 2, 3)) {
                return@withContext "This Seerr server uses Plex, not Jellyfin."
            }
            val res = call(base, null, "POST", "/api/v1/auth/jellyfin",
                JSONObject().put("username", username).put("password", password))
            val cookie = res.cookie ?: return@withContext "Seerr didn't start a session. Check the address."
            val user = JSONObject(res.body)
            val name = user.optString("displayName").ifEmpty { user.optString("jellyfinUsername") }.ifEmpty { username }
            prefs.update { it.copy(seerr = SeerrAccount(base, cookie, user.optInt("id"), name)) }
            signedOutState.value = false
            titles.clear()
            null
        } catch (e: HttpStatusException) {
            when (e.code) {
                401, 403 -> "Wrong Jellyfin username or password, or this user isn't allowed to use Seerr."
                404 -> "That address doesn't look like a Seerr server."
                else -> "Seerr returned HTTP ${e.code}."
            }
        } catch (e: Exception) {
            "Couldn't reach Seerr (${e.message ?: "offline"})."
        }
    }

    fun signOut() {
        val account = prefs.config.value.seerr ?: return
        prefs.update { it.copy(seerr = null) }
        titles.clear()
        signedOutState.value = false
        Thread { runCatching { call(account.url, account.cookie, "POST", "/api/v1/auth/logout", JSONObject()) } }.start()
    }

    // ---------------------------------------------------------------- library

    suspend fun search(query: String): List<SeerrResult> = withContext(Dispatchers.IO) {
        // Seerr wants %20, not '+', for spaces.
        val q = URLEncoder.encode(query.trim().take(100), "UTF-8").replace("+", "%20")
        val o = JSONObject(api("GET", "/api/v1/search?query=$q&page=1&language=${lang()}", null))
        val arr = o.optJSONArray("results") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("mediaType") in setOf("movie", "tv") }
            .map { r ->
                val movie = r.getString("mediaType") == "movie"
                SeerrResult(
                    type = r.getString("mediaType"),
                    id = r.getInt("id"),
                    title = r.optString(if (movie) "title" else "name"),
                    year = r.optString(if (movie) "releaseDate" else "firstAirDate").take(4),
                    overview = r.optString("overview"),
                    poster = r.optString("posterPath").ifEmpty { null },
                    backdrop = r.optString("backdropPath").ifEmpty { null },
                    rating = r.optDouble("voteAverage", 0.0),
                    state = mediaState(r.optJSONObject("mediaInfo")),
                )
            }
    }

    /** Library and request status of one title (cached for a few minutes). */
    suspend fun title(type: String, id: Int, fresh: Boolean = false): SeerrTitle = withContext(Dispatchers.IO) {
        val key = "$type:$id"
        if (!fresh) titles[key]?.let { (at, t) -> if (System.currentTimeMillis() - at < CACHE_MS) return@withContext t }
        val o = JSONObject(api("GET", "/api/v1/$type/$id?language=${lang()}", null))
        val info = o.optJSONObject("mediaInfo")
        val seasons = if (type != "tv") emptyList() else {
            val perSeason = seasonStates(info)
            val arr = o.optJSONArray("seasons") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optInt("seasonNumber") > 0 }
                .map { s ->
                    val n = s.optInt("seasonNumber")
                    SeerrSeason(n, s.optString("name").ifEmpty { "Season $n" }, s.optInt("episodeCount"), perSeason[n] ?: SeerrState.NONE)
                }
        }
        SeerrTitle(type, id, mediaState(info), seasons).also { titles[key] = System.currentTimeMillis() to it }
    }

    /** Requests a movie, or the given seasons of a series (all missing ones when [seasons] is null). */
    suspend fun request(type: String, id: Int, seasons: List<Int>? = null): SeerrTitle = withContext(Dispatchers.IO) {
        val body = JSONObject().put("mediaType", type).put("mediaId", id)
        if (type == "tv") body.put("seasons", seasons?.let { JSONArray(it) } ?: "all")
        api("POST", "/api/v1/request", body)
        title(type, id, fresh = true)
    }

    // ---------------------------------------------------------------- plumbing

    private fun api(method: String, path: String, body: JSONObject?): String {
        val account = prefs.config.value.seerr ?: throw SeerrSignedOut()
        return try {
            val res = call(account.url, account.cookie, method, path, body)
            // Seerr's session is rolling: keep the refreshed cookie so it doesn't lapse while in use.
            if (res.cookie != null && res.cookie != account.cookie) {
                prefs.update { c -> c.copy(seerr = c.seerr?.takeIf { it.cookie == account.cookie }?.copy(cookie = res.cookie) ?: c.seerr) }
            }
            res.body
        } catch (e: HttpStatusException) {
            // A 403 on a request is a quota or permission answer, not a lost session.
            if (e.code == 401 || (e.code == 403 && path != "/api/v1/request")) {
                signedOutState.value = true
                throw SeerrSignedOut()
            }
            throw e
        }
    }

    private class Res(val body: String, val cookie: String?)

    private fun call(base: String, cookie: String?, method: String, path: String, body: JSONObject?): Res {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DCLauncher (Android TV)")
            if (cookie != null) setRequestProperty("Cookie", cookie)
        }
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json;charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            val session = conn.headerFields.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
                ?.firstOrNull { it.startsWith("connect.sid=") }?.substringBefore(';')
            return Res(conn.inputStream.bufferedReader().use { it.readText() }, session)
        } finally {
            conn.disconnect()
        }
    }

    private fun mediaState(info: JSONObject?): SeerrState {
        if (info == null) return SeerrState.NONE
        val requests = info.optJSONArray("requests")
        val requestStates = (0 until (requests?.length() ?: 0)).map { requests!!.getJSONObject(it).optInt("status") }
        return when (info.optInt("status")) {
            5 -> SeerrState.AVAILABLE
            4 -> SeerrState.PARTIAL
            3 -> SeerrState.PROCESSING
            2 -> SeerrState.PENDING
            else -> when {
                requestStates.any { it == 1 } -> SeerrState.PENDING
                requestStates.any { it == 2 } -> SeerrState.PROCESSING
                requestStates.isNotEmpty() && requestStates.all { it == 3 } -> SeerrState.DECLINED
                else -> SeerrState.NONE
            }
        }
    }

    /** Per-season state from the library, then from requests for seasons not in it yet. */
    private fun seasonStates(info: JSONObject?): Map<Int, SeerrState> {
        if (info == null) return emptyMap()
        val out = HashMap<Int, SeerrState>()
        info.optJSONArray("seasons")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                out[s.optInt("seasonNumber")] = when (s.optInt("status")) {
                    5 -> SeerrState.AVAILABLE; 4 -> SeerrState.PARTIAL; 3 -> SeerrState.PROCESSING; 2 -> SeerrState.PENDING
                    else -> SeerrState.NONE
                }
            }
        }
        info.optJSONArray("requests")?.let { reqs ->
            for (i in 0 until reqs.length()) {
                val r = reqs.getJSONObject(i)
                val st = when (r.optInt("status")) { 1 -> SeerrState.PENDING; 2 -> SeerrState.PROCESSING; else -> null } ?: continue
                val seasons = r.optJSONArray("seasons") ?: continue
                for (j in 0 until seasons.length()) {
                    val n = seasons.getJSONObject(j).optInt("seasonNumber")
                    if ((out[n] ?: SeerrState.NONE) == SeerrState.NONE) out[n] = st
                }
            }
        }
        return out
    }

    private fun lang() = Locale.getDefault().language.ifEmpty { "en" }

    companion object {
        private const val CACHE_MS = 5 * 60_000L

        /**
         * "192.168.0.20:5055" → "http://192.168.0.20:5055". Plain http is accepted only for
         * home-network hosts (private IPs, .local/.lan names or single-word names); anything on
         * the internet must use https so the session cookie never crosses it unencrypted.
         */
        fun normalise(raw: String): String? = runCatching {
            val withScheme = raw.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
            val uri = URI(withScheme)
            val host = uri.host ?: return null
            when (uri.scheme?.lowercase()) {
                "https" -> {}
                "http" -> if (!isHomeNetwork(host)) return null
                else -> return null
            }
            withScheme.substringBefore('?').substringBefore('#')
        }.getOrNull()

        private fun isHomeNetwork(host: String): Boolean {
            val h = host.lowercase()
            if ('.' !in h || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
            if (!h.all { it.isDigit() || it == '.' }) return false
            val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
            return a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress
        }
    }
}

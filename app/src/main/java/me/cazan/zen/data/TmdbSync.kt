package me.cazan.zen.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Two-way sync between the TV's watchlist and the user's TMDB account watchlist.
 *
 * Sign-in uses TMDB's own approval page: the TV asks for a request token, the user opens
 * themoviedb.org/authenticate/<token> on their phone (QR code), signs in there and presses
 * Approve, and the TV then exchanges the token for a session. The TV never sees the password.
 *
 * Sync rule: a title on only one side is copied across, unless it was on both sides at the last
 * sync, in which case it was removed on the other side and is removed here too.
 */
class TmdbSync(private val prefs: LauncherPrefs, private val tmdb: SpotlightRepository) {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val statusState = MutableStateFlow("")

    val status: StateFlow<String> = statusState.asStateFlow()

    /** Pushes local watchlist changes (from the home screen, Settings or Web Admin) shortly after they happen. */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            prefs.config
                .map { c -> c.tmdbAccount?.let { a -> c.watchlist.map { it.key }.toSet() to a.synced } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1_500)
                .collect { pair ->
                    val (local, synced) = pair ?: return@collect
                    if (local != synced) sync()
                }
        }
    }

    // ---------------------------------------------------------------- sign-in

    /** Step 1: a request token, and the page the user must open to approve it. */
    suspend fun beginSignIn(key: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val token = tmdb.tmdbSend(key, "GET", "/authentication/token/new", null).getString("request_token")
        token to "https://www.themoviedb.org/authenticate/$token"
    }

    /**
     * Step 2, polled while the QR code is showing: null until the user has approved the token
     * on TMDB, then the connected account (already saved).
     */
    suspend fun finishSignIn(key: String, token: String): TmdbAccount? = withContext(Dispatchers.IO) {
        val session = try {
            tmdb.tmdbSend(key, "POST", "/authentication/session/new", JSONObject().put("request_token", token))
                .getString("session_id")
        } catch (e: HttpStatusException) {
            if (e.code == 401) return@withContext null else throw e
        }
        val acct = tmdb.tmdbSend(key, "GET", "/account?session_id=$session", null)
        val account = TmdbAccount(
            sessionId = session,
            accountId = acct.getInt("id"),
            username = acct.optString("username").ifEmpty { acct.optString("name") },
        )
        prefs.update { it.copy(tmdbAccount = account) }
        statusState.value = ""
        account
    }

    /** Forgets the session on the TV and asks TMDB to end it. The watchlist on both sides stays as it is. */
    fun disconnect() {
        val account = prefs.config.value.tmdbAccount ?: return
        val key = prefs.config.value.tmdbKey
        prefs.update { it.copy(tmdbAccount = null) }
        statusState.value = ""
        if (key != null) scope.launch {
            runCatching {
                tmdb.tmdbSend(key, "DELETE", "/authentication/session", JSONObject().put("session_id", account.sessionId))
            }
        }
    }

    // ---------------------------------------------------------------- sync

    suspend fun sync(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val c = prefs.config.value
            val account = c.tmdbAccount ?: return@withContext
            val key = c.tmdbKey ?: run { statusState.value = "Add a TMDB key in Web Admin to sync"; return@withContext }
            statusState.value = "Syncing…"
            runCatching { syncOnce(key, account, c.watchlist) }
                .onSuccess { statusState.value = it }
                .onFailure { e ->
                    statusState.value = when ((e as? HttpStatusException)?.code) {
                        401 -> "TMDB refused access. Disconnect and connect the account again"
                        else -> "Couldn't reach TMDB (${e.message ?: "offline"})"
                    }
                }
        }
    }

    private fun syncOnce(key: String, account: TmdbAccount, localItems: List<WatchItem>): String {
        val remote = fetchRemote(key, account)
        val local = localItems.map { it.key }.toSet()
        val synced = account.synced

        val onlyLocal = local - remote.keys
        val onlyRemote = remote.keys - local
        val removeHere = onlyLocal intersect synced     // removed on TMDB since the last sync
        val pushAdd = onlyLocal - synced                // added on the TV
        val pushRemove = onlyRemote intersect synced    // removed on the TV
        val pull = onlyRemote - synced                  // added on TMDB

        val added = pushAdd.filter { setRemote(key, account, it, true) }.toSet()
        val unwatched = pushRemove.filter { setRemote(key, account, it, false) }.toSet()
        // A failed removal stays "synced" so it's retried; a failed addition is retried as new.
        val bothNow = (local intersect remote.keys) + added + pull + (pushRemove - unwatched)

        val pulled = remote.values.filter { it.key in pull }
        prefs.update { cur ->
            val kept = cur.watchlist.filterNot { it.key in removeHere }
            val fresh = pulled.filter { p -> kept.none { it.key == p.key } }
            val list = fresh + kept
            val keys = list.map { it.key }.toSet()
            cur.copy(
                watchlist = list,
                notInterested = cur.notInterested - fresh.map { it.key }.toSet(),
                // Keep the session that's current now (it may have been replaced or removed meanwhile).
                tmdbAccount = cur.tmdbAccount?.takeIf { it.sessionId == account.sessionId }?.copy(
                    synced = bothNow.filter { it in keys || it in (pushRemove - unwatched) }.toSet(),
                    syncedAt = System.currentTimeMillis(),
                ) ?: cur.tmdbAccount,
            )
        }

        val changes = listOfNotNull(
            pull.size.takeIf { it > 0 }?.let { "$it added from TMDB" },
            removeHere.size.takeIf { it > 0 }?.let { "$it removed on TMDB" },
            (added.size + unwatched.size).takeIf { it > 0 }?.let { "$it sent to TMDB" },
        )
        val failed = (pushAdd - added).size + (pushRemove - unwatched).size
        return buildString {
            append(if (changes.isEmpty()) "Up to date" else changes.joinToString(", "))
            if (failed > 0) append(" · $failed couldn't be sent, will retry")
        }
    }

    /** Both TMDB watchlists (movies and series), newest first, as watchlist items. */
    private fun fetchRemote(key: String, account: TmdbAccount): Map<String, WatchItem> {
        val lang = Locale.getDefault().language.ifEmpty { "en" }
        val out = LinkedHashMap<String, WatchItem>()
        val now = System.currentTimeMillis()
        for ((type, path) in listOf("movie" to "movies", "tv" to "tv")) {
            var page = 1
            do {
                val o = tmdb.tmdbSend(
                    key, "GET",
                    "/account/${account.accountId}/watchlist/$path?session_id=${account.sessionId}" +
                        "&language=$lang&sort_by=created_at.desc&page=$page",
                    null,
                )
                val results = o.optJSONArray("results") ?: break
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val movie = type == "movie"
                    val item = WatchItem(
                        type = type,
                        id = r.getInt("id"),
                        title = r.optString(if (movie) "title" else "name"),
                        year = r.optString(if (movie) "release_date" else "first_air_date").take(4),
                        backdrop = r.optString("backdrop_path").ifEmpty { null },
                        overview = r.optString("overview"),
                        addedAt = now,
                    )
                    out[item.key] = item
                }
                val pages = o.optInt("total_pages", 1)
                page++
            } while (page <= pages && page <= MAX_PAGES)
        }
        return out
    }

    private fun setRemote(key: String, account: TmdbAccount, itemKey: String, on: Boolean): Boolean = runCatching {
        val (type, id) = itemKey.split(':').let { it[0] to it[1].toInt() }
        tmdb.tmdbSend(
            key, "POST", "/account/${account.accountId}/watchlist?session_id=${account.sessionId}",
            JSONObject().put("media_type", type).put("media_id", id).put("watchlist", on),
        )
    }.isSuccess

    private companion object {
        const val MAX_PAGES = 10
    }
}

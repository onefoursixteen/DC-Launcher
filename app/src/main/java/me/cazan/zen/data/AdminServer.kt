package me.cazan.zen.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections

/**
 * A deliberately tiny web page for things that are painful on a remote: the TMDB key,
 * searching for titles and managing the watchlist.
 *
 * Off by default. Turned on from Settings, it listens on the LAN only while Settings is open,
 * shuts itself down after 15 minutes, and stops after 5 wrong PINs. The PIN shown on the TV
 * buys a session cookie (SameSite=Strict, valid until the server stops). One request at a
 * time, no scripts, nothing loaded from elsewhere except TMDB poster thumbnails.
 */
class AdminServer(
    private val context: Context,
    private val prefs: LauncherPrefs,
    private val spotlight: SpotlightRepository,
    private val watchlist: WatchlistRepository,
    private val apps: AppRepository,
) {
    data class State(val running: Boolean = false, val url: String? = null, val pin: String? = null, val event: String = "")

    private class Request(val method: String, val path: String, val query: Map<String, String>, val cookies: Map<String, String>, val form: Map<String, String>)
    private class Response(val code: Int, val body: String = "", val headers: List<String> = emptyList())

    private val state = MutableStateFlow(State())
    private val main = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop("Turned off after 15 minutes") }
    private val random = SecureRandom()
    private val sessions: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile private var server: ServerSocket? = null
    private var failures = 0

    val status: StateFlow<State> = state.asStateFlow()

    @Synchronized
    fun start() {
        if (server != null) return
        val socket = runCatching { ServerSocket(PORT) }.recoverCatching { ServerSocket(0) }.getOrNull()
        if (socket == null) {
            state.value = State(event = "Couldn't open a network port")
            return
        }
        server = socket
        failures = 0
        sessions.clear()
        val pin = (random.nextInt(900_000) + 100_000).toString()
        val ip = localIpv4() ?: "<TV IP address>"
        state.value = State(true, "http://$ip:${socket.localPort}", pin)
        Thread({ serve(socket, pin) }, "zen-admin").apply { isDaemon = true }.start()
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, 15 * 60_000L)
    }

    @Synchronized
    fun stop(reason: String = "") {
        main.removeCallbacks(autoStop)
        server?.let { runCatching { it.close() } }
        server = null
        sessions.clear()
        state.value = State(event = reason)
    }

    private fun event(text: String) = main.post { if (state.value.running) state.value = state.value.copy(event = text) }

    private fun serve(socket: ServerSocket, pin: String) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching { client.use { handle(it, pin) } }
        }
    }

    // ---------------------------------------------------------------- routing

    private fun handle(client: Socket, pin: String) {
        client.soTimeout = 10_000
        val req = readRequest(client.getInputStream()) ?: return
        val authed = req.cookies["zen"]?.let { it in sessions } == true
        val res = when {
            req.method == "POST" && req.path == "/login" -> login(req, pin)
            !authed -> if (req.method == "GET" && req.path == "/") Response(200, loginPage(null)) else redirect("/")
            req.method == "GET" && req.path == "/" -> Response(200, mainPage(req.query["m"], req.query["e"] == "1", null, null))
            req.method == "GET" && req.path == "/search" -> search(req.query["q"].orEmpty(), req.query["m"])
            req.method == "POST" && req.path == "/settings" -> saveSettings(req.form)
            req.method == "POST" && req.path == "/watch/add" -> addWatch(req.form)
            req.method == "POST" && req.path == "/watch/remove" -> removeWatch(req.form)
            req.method == "POST" && req.path == "/interest/reset" -> {
                prefs.update { it.copy(notInterested = emptySet()) }
                redirect("/?m=" + enc("Hidden titles will show in the spotlight again."))
            }
            else -> Response(404, "Not found")
        }
        val bytes = res.body.toByteArray(Charsets.UTF_8)
        val status = when (res.code) { 200 -> "OK"; 303 -> "See Other"; else -> "Not Found" }
        val out = client.getOutputStream()
        out.write(("HTTP/1.1 ${res.code} $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Frame-Options: DENY\r\n" +
            "Referrer-Policy: no-referrer\r\nConnection: close\r\n" +
            res.headers.joinToString("") { "$it\r\n" } + "\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun login(req: Request, pin: String): Response {
        if (req.form["pin"]?.trim() != pin) {
            failures++
            if (failures >= MAX_FAILURES) {
                main.post { stop("Turned off after too many wrong PINs") }
                return Response(200, loginPage("Wrong PIN. The admin page is now turned off."))
            }
            return Response(200, loginPage("Wrong PIN. Check the number shown on the TV."))
        }
        failures = 0
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        sessions += token
        event("A device signed in to the web page")
        return redirect("/", "Set-Cookie: zen=$token; Path=/; HttpOnly; SameSite=Strict")
    }

    private fun saveSettings(form: Map<String, String>): Response {
        if (form["action"] == "remove") {
            prefs.update { it.copy(tmdbKey = null) }
            spotlight.clear()
            event("TMDB key removed from the web page")
            return redirect("/?m=" + enc("TMDB key removed."))
        }
        val spot = form["spotlight"] == "on"
        val mine = form["only_mine"] == "on"
        val key = form["tmdb_key"]?.trim().orEmpty()
        if (key.isNotEmpty()) {
            if (key.length > 1000 || key.any { it.isWhitespace() }) return redirect("/?e=1&m=" + enc("That doesn't look like a TMDB key."))
            val problem = runBlocking { spotlight.validateKey(key) }
            if (problem != null) return redirect("/?e=1&m=" + enc("$problem. Nothing was saved."))
            prefs.update { it.copy(tmdbKey = key, spotlight = spot, onlyMine = mine) }
        } else {
            prefs.update { it.copy(spotlight = spot, onlyMine = mine) }
        }
        event("Settings saved from the web page")
        return redirect("/?m=" + enc(if (key.isNotEmpty()) "Key checked with TMDB and saved." else "Saved."))
    }

    private fun search(q: String, message: String?): Response {
        val key = prefs.config.value.tmdbKey ?: return redirect("/?e=1&m=" + enc("Add a TMDB key first."))
        if (q.isBlank()) return redirect("/")
        val results = runBlocking { watchlist.search(key, q) }
        return Response(200, mainPage(message, false, q, results))
    }

    private fun addWatch(form: Map<String, String>): Response {
        val key = prefs.config.value.tmdbKey ?: return redirect("/?e=1&m=" + enc("Add a TMDB key first."))
        val id = form["id"]?.toIntOrNull()
        val type = form["type"]
        val item = (if (id != null && type != null) runBlocking { watchlist.lookup(key, type, id) } else null)
            ?: return redirect("/?e=1&m=" + enc("Couldn't add that title."))
        prefs.addToWatchlist(item)
        event("Added “${item.title}” to the watchlist")
        val q = form["q"]?.takeIf { it.isNotBlank() }
        val msg = "m=" + enc("Added “${item.title}”.")
        return redirect(if (q != null) "/search?q=${enc(q)}&$msg" else "/?$msg")
    }

    private fun removeWatch(form: Map<String, String>): Response {
        val k = form["key"] ?: return redirect("/")
        val title = prefs.config.value.watchlist.firstOrNull { it.key == k }?.title
        prefs.removeFromWatchlist(k)
        return redirect("/?m=" + enc("Removed “${title ?: "title"}”.") + "#watchlist")
    }

    private fun redirect(to: String, vararg extra: String) = Response(303, "", listOf("Location: $to") + extra)

    // ---------------------------------------------------------------- HTML

    private fun loginPage(message: String?) = shell(
        """<h1>DC Launcher</h1><p>Enter the PIN shown on the TV.</p>
${message?.let { """<p class="banner err">${esc(it)}</p>""" }.orEmpty()}
<form method="post" action="/login" autocomplete="off" class="inline">
<input name="pin" type="text" inputmode="numeric" maxlength="6" placeholder="6-digit PIN" required autofocus>
<button class="primary">Continue</button></form>"""
    )

    private fun mainPage(message: String?, isError: Boolean, query: String?, results: List<SearchResult>?): String {
        val c = prefs.config.value
        val installed = apps.apps.value
        val statuses = watchlist.status.value
        val region = c.place?.let { p ->
            p.countryCode?.let { "${p.country ?: it} ($it), from the weather city ${p.name}" }
                ?: "Looking up the country of ${p.name}…"
        } ?: "Not set yet. Choose a weather city on the TV (Settings → Clock & weather)."
        val keyState = c.tmdbKey?.let { "Saved (…${esc(it.takeLast(4))})" } ?: "Not set"
        val banner = message?.let { """<p class="banner${if (isError) " err" else ""}">${esc(it)}</p>""" }.orEmpty()
        val listed = c.watchlist.mapTo(HashSet()) { it.key }

        val searchHtml = if (c.tmdbKey == null) "<p>Add your TMDB key below to search.</p>" else """
<form method="get" action="/search" class="inline"><input name="q" type="text" placeholder="Movie or series" value="${esc(query.orEmpty())}" required><button class="primary">Search</button></form>
${results?.let { list ->
    if (list.isEmpty()) "<p>No results.</p>" else list.joinToString("", "<ul class=\"cards\">", "</ul>") { r ->
        val action = if ("${r.type}:${r.id}" in listed) "<span class=\"tag\">On watchlist</span>" else """<form method="post" action="/watch/add">
<input type="hidden" name="type" value="${r.type}"><input type="hidden" name="id" value="${r.id}"><input type="hidden" name="q" value="${esc(query.orEmpty())}">
<button class="small">Add</button></form>"""
        """<li>${poster(r.poster)}<div class="meta"><b>${esc(r.title)}</b><span>${if (r.type == "movie") "Movie" else "Series"}${if (r.year.isNotEmpty()) " · ${r.year}" else ""}</span></div>$action</li>"""
    }
}.orEmpty()}"""

        val watchHtml = if (c.watchlist.isEmpty()) "<p>Nothing yet. Search above, or press OK on a spotlight title on the TV.</p>"
        else c.watchlist.joinToString("", "<ul class=\"cards\">", "</ul>") { w ->
            val label = watchLabel(w, statuses[w.key], installed)
            val detail = watchDetail(w, statuses[w.key])?.takeIf { it != label.text }
            """<li><div class="meta"><b>${esc(w.title)}</b><span${if (label.watchable) " class=\"good\"" else ""}>${esc(label.text)}</span>${detail?.let { "<span>${esc(it).replace("\n", "<br>")}</span>" }.orEmpty()}</div>
<form method="post" action="/watch/remove"><input type="hidden" name="key" value="${esc(w.key)}"><button class="small ghost">Remove</button></form></li>"""
        }

        return shell("""<h1>DC Launcher</h1><p>Spotlight and watchlist for your TV.</p>
$banner
<dl><dt>TMDB key</dt><dd>$keyState</dd><dt>Region</dt><dd>${esc(region)}</dd><dt>Watchlist</dt><dd>${c.watchlist.size} title${if (c.watchlist.size == 1) "" else "s"}</dd></dl>

<h2>Search</h2>$searchHtml

<h2 id="watchlist">Watchlist</h2>$watchHtml

<h2>Spotlight</h2>
<form method="post" action="/settings" autocomplete="off">
<label for="key">TMDB API key or Read Access Token</label>
<input id="key" name="tmdb_key" type="password" placeholder="${if (c.tmdbKey != null) "Leave empty to keep the saved key" else "Paste your key"}">
<small>Get one free at <a href="https://www.themoviedb.org/settings/api" target="_blank" rel="noopener">themoviedb.org → Settings → API</a>.</small>
<div class="row"><input id="sp" name="spotlight" type="checkbox"${if (c.spotlight || c.tmdbKey == null) " checked" else ""}><label for="sp">Show the spotlight on the home screen</label></div>
<div class="row"><input id="om" name="only_mine" type="checkbox"${if (c.onlyMine) " checked" else ""}><label for="om">Only show titles on my streaming apps</label></div>
<button class="primary" name="action" value="save">Save</button>${if (c.tmdbKey != null) """<button class="ghost" name="action" value="remove">Remove key</button>""" else ""}
</form>
${if (c.notInterested.isNotEmpty()) """<form method="post" action="/interest/reset" class="inline" style="margin-top:16px"><span>${c.notInterested.size} titles hidden with “Not interested”.</span><button class="small ghost">Show them again</button></form>""" else ""}
<p><small>This product uses the TMDB API but is not endorsed or certified by TMDB.</small></p>""")
    }

    private fun poster(path: String?) =
        if (path == null) "<div class=\"poster\"></div>"
        else """<img class="poster" src="https://image.tmdb.org/t/p/w92${esc(path)}" alt="" loading="lazy">"""

    private fun shell(body: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DC Launcher admin</title>
<style>
:root{--bg:#0e1116;--panel:#181c24;--text:#f2f4f8;--dim:#a9afbb;--accent:#8ab4ff;--ok:#7fd49b;--err:#ff9b8a;--line:#2a303b}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,sans-serif;padding:16px}
main{max-width:620px;margin:16px auto;background:var(--panel);border-radius:16px;padding:24px}
h1{font-weight:400;margin:0 0 4px}h2{font-size:18px;margin:28px 0 10px;padding-top:16px;border-top:1px solid var(--line)}
p{color:var(--dim);margin:6px 0 14px}label{display:block;margin:14px 0 6px;font-weight:600}
input[type=text],input[type=password]{width:100%;padding:12px;border-radius:10px;border:1px solid #333a46;background:#0b0e13;color:var(--text);font-size:16px}
.row{display:flex;gap:10px;align-items:center;margin-top:14px}.row label{margin:0;font-weight:400}
.inline{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.inline input{flex:1;min-width:0;width:auto}.inline span{color:var(--dim)}
button{margin-top:16px;padding:12px 20px;border:0;border-radius:999px;font-size:16px;font-weight:600;cursor:pointer}
.inline button{margin-top:0}.primary{background:var(--text);color:var(--bg)}
.ghost{background:transparent;color:var(--dim);border:1px solid #333a46;margin-left:8px}
.small{margin:0;padding:8px 14px;font-size:14px;background:var(--text);color:var(--bg)}.small.ghost{background:transparent;color:var(--dim)}
.banner{padding:12px;border-radius:10px;color:var(--bg);background:var(--ok)}.banner.err{background:var(--err)}
dl{display:grid;grid-template-columns:auto 1fr;gap:4px 16px;margin:16px 0;color:var(--dim)}dt{color:var(--text)}
ul.cards{list-style:none;margin:0;padding:0}ul.cards li{display:flex;gap:12px;align-items:center;padding:10px 0;border-bottom:1px solid var(--line)}
.poster{width:46px;height:69px;border-radius:6px;background:#0b0e13;object-fit:cover;flex:none}
.meta{flex:1;min-width:0;display:flex;flex-direction:column}.meta span{color:var(--dim);font-size:14px}.meta span.good{color:var(--ok)}
.tag{color:var(--dim);font-size:14px}small{color:var(--dim)}a{color:var(--accent)}
</style></head><body><main>
$body
</main></body></html>"""

    // ---------------------------------------------------------------- plumbing

    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val target = parts[1]
        val headers = lines.drop(1).mapNotNull { l ->
            l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() }
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val cookies = headers["cookie"].orEmpty().split(';').mapNotNull { c ->
            c.indexOf('=').takeIf { it > 0 }?.let { c.substring(0, it).trim() to c.substring(it + 1).trim() }
        }.toMap()
        return Request(
            method = parts[0],
            path = target.substringBefore('?'),
            query = decodeForm(target.substringAfter('?', "")),
            cookies = cookies,
            form = if (length > 0) decodeForm(readBody(input, length)) else emptyMap(),
        )
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (buf.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toChar() == end[matched]) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == 4) return buf.toString("ISO-8859-1").removeSuffix(end)
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun decodeForm(s: String): Map<String, String> = s.split('&').mapNotNull { pair ->
        val k = pair.substringBefore('=', "")
        if (k.isEmpty()) null
        else runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
    }.toMap()

    private fun localIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        const val PORT = 8321
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 8 * 1024
        const val MAX_FAILURES = 5
    }
}

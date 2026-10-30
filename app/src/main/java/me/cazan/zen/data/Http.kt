package me.cazan.zen.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Plain HttpURLConnection: two tiny GET calls don't justify shipping OkHttp.

private fun open(url: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 6_000
        readTimeout = 10_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "DCLauncher/1.0 (Android TV)")
    }

class HttpStatusException(val code: Int) : IOException("HTTP $code")

internal fun httpGetText(url: String, headers: Map<String, String> = emptyMap()): String {
    val conn = open(url)
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    try {
        if (conn.responseCode !in 200..299) throw HttpStatusException(conn.responseCode)
        return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

internal fun httpDownload(url: String, dest: File, maxBytes: Long) {
    val conn = open(url)
    val tmp = File(dest.parentFile, dest.name + ".part")
    try {
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("Image too large")
                    output.write(buf, 0, n)
                }
            }
        }
        if (!tmp.renameTo(dest)) throw IOException("Couldn't save image")
    } finally {
        tmp.delete()
        conn.disconnect()
    }
}

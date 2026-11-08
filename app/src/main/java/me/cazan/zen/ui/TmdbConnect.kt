package me.cazan.zen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.cazan.zen.data.HttpStatusException
import me.cazan.zen.data.TmdbSync

/**
 * Connects a TMDB account: shows a QR code for TMDB's own approval page, then waits until the
 * user has approved it on their phone. The password is only ever typed on themoviedb.org.
 */
@Composable
fun TmdbConnectDialog(sync: TmdbSync, key: String, onConnected: () -> Unit, onClose: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        error = null
        url = null
        val (token, page) = try {
            sync.beginSignIn(key)
        } catch (e: Exception) {
            error = if ((e as? HttpStatusException)?.code == 401) "TMDB rejected the API key"
            else "Couldn't reach TMDB (${e.message ?: "offline"})"
            return@LaunchedEffect
        }
        url = page
        // Request tokens last 60 minutes; stop asking well before that.
        repeat(POLLS) {
            delay(POLL_MS)
            val account = runCatching { sync.finishSignIn(key, token) }.getOrNull()
            if (account != null) { onConnected(); return@LaunchedEffect }
        }
        error = "The code expired"
    }

    ZenDialog("Connect TMDB account", onClose, width = 620.dp, initialFocus = first, scrollable = false) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val page = url
            val problem = error
            when {
                problem != null -> {
                    Txt(problem, size = 18.sp, color = Zen.Accent, align = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                }
                page == null -> {
                    Txt("Asking TMDB for a sign-in code…", color = Zen.TextDim)
                    Spacer(Modifier.height(14.dp))
                }
                else -> {
                    Note("Scan with your phone, sign in to TMDB and press Approve", color = Zen.TextDim)
                    Spacer(Modifier.height(10.dp))
                    QrCode(page)
                    Spacer(Modifier.height(12.dp))
                    Txt("Waiting for approval…", size = 18.sp, color = Zen.Accent)
                    Spacer(Modifier.height(14.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ZenButton("Cancel", onClose, Modifier.focusRequester(first))
                if (problem != null) ZenButton("Try again", { attempt++ })
            }
            Spacer(Modifier.height(10.dp))
            Txt("Your password is typed only on themoviedb.org, never on the TV", size = 13.sp, color = Zen.TextFaint)
        }
    }
}

private const val POLL_MS = 3_000L
private const val POLLS = 15 * 60 * 1000 / 3_000 // 15 minutes

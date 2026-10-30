package me.cazan.zen.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.cazan.zen.data.AppEntry
import me.cazan.zen.data.SpotlightItem
import me.cazan.zen.data.SpotlightRepository
import me.cazan.zen.data.providerApps
import java.util.Locale

private val Horizontal = Brush.horizontalGradient(
    0f to Color(0xF00E1116), 0.38f to Color(0xA60E1116), 0.75f to Color(0x000E1116),
)
private val Vertical = Brush.verticalGradient(
    0f to Color(0x660E1116), 0.18f to Color(0x000E1116), 0.38f to Color(0x000E1116),
    0.62f to Color(0xF20E1116), 1f to Color(0xFA0E1116),
)

/** Full-screen artwork behind the top of the home screen, fading into the background below. */
@Composable
fun SpotlightBackdrop(repo: SpotlightRepository, item: SpotlightItem?, shown: Boolean) {
    val alpha by animateFloatAsState(if (shown && item != null) 1f else 0f, tween(450), label = "spotAlpha")
    if (alpha == 0f) return
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        Crossfade(targetState = item, animationSpec = tween(800), label = "spotFade") { current ->
            if (current != null) {
                val image by produceState(repo.cachedBackdrop(current), current.backdrop) {
                    if (value == null) value = repo.backdrop(current)
                }
                image?.let {
                    Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Horizontal))
        Box(Modifier.fillMaxSize().background(Vertical))
    }
}

/**
 * The focusable title block. LEFT/RIGHT browse, OK opens details. Auto-rotation is paused
 * by the caller while this has focus.
 */
@Composable
fun SpotlightPanel(
    repo: SpotlightRepository,
    item: SpotlightItem,
    index: Int,
    count: Int,
    installed: List<AppEntry>,
    onFocusChanged: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // Keyed state: produceState would keep the previous title's logo when the item changes.
    var logo by remember(item.logo) { mutableStateOf(repo.cachedLogo(item)) }
    LaunchedEffect(item.logo) { if (logo == null && item.logo != null) logo = repo.logo(item) }
    val onApps = remember(item, installed) { providerApps(item.providers, installed) }

    Column(
        modifier
            .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) }
            .onKeyEvent { ev ->
                val native = ev.nativeKeyEvent
                val down = ev.type == KeyEventType.KeyDown
                when (native.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) onStep(-1); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) onStep(1); true }
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER,
                    NativeKeyEvent.KEYCODE_NUMPAD_ENTER, NativeKeyEvent.KEYCODE_BUTTON_A -> {
                        if (down && native.repeatCount == 0) pressed = true
                        if (!down && pressed) { pressed = false; onOpen() }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(item.id) { detectTapGestures(onTap = { onOpen() }) }
            .clip(Zen.Card)
            .background(if (focused) Color(0x24FFFFFF) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Txt(item.reason.uppercase(Locale.getDefault()), size = 12.sp, color = Zen.Accent, weight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.heightIn(min = 34.dp, max = 46.dp), contentAlignment = Alignment.CenterStart) {
            val l = logo
            if (l != null) {
                Image(l, item.title, Modifier.heightIn(max = 46.dp).widthIn(max = 300.dp),
                    contentScale = ContentScale.Fit, alignment = Alignment.CenterStart)
            } else {
                Txt(item.title, size = 28.sp, weight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Txt(metaLine(item), size = 15.sp, color = Zen.TextDim)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                onApps.isNotEmpty() -> Badge("▶  On ${onApps.joinToString(" · ") { it.first }}", strong = true)
                item.providers.isNotEmpty() -> Badge("On ${item.providers.take(2).joinToString(" · ")}", strong = false)
            }
            if (count > 1) Dots(index, count)
            if (focused) Txt("◀ ▶ browse  ·  OK details", size = 13.sp, color = Zen.TextFaint)
        }
    }
}

@Composable
private fun Badge(text: String, strong: Boolean) {
    Box(
        Modifier
            .clip(Zen.Pill)
            .background(if (strong) Zen.Accent else Zen.Surface)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Txt(text, size = 13.sp, color = if (strong) Zen.OnFocus else Zen.Text, weight = FontWeight.Medium)
    }
}

@Composable
private fun Dots(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == index) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (i == index) Zen.Text else Zen.TextFaint)
            )
        }
    }
}

private fun metaLine(item: SpotlightItem): String = listOfNotNull(
    if (item.type == "movie") "Movie" else "Series",
    item.year.ifEmpty { null },
    item.genres.joinToString(", ").ifEmpty { null },
    if (item.rating > 0) "★ " + String.format(Locale.US, "%.1f", item.rating) else null,
).joinToString("  ·  ")

@Composable
fun SpotlightDialog(
    item: SpotlightItem,
    installed: List<AppEntry>,
    region: String?,
    onOpenApp: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    inWatchlist: Boolean,
    onToggleWatchlist: () -> Unit,
    /** Shown for spotlight titles only; hides the title from the spotlight for good. */
    onNotInterested: (() -> Unit)? = null,
    /** Today's watchlist status, e.g. "New episode Thursday · Netflix". */
    status: String? = null,
) {
    val first = remember { FocusRequester() }
    val onApps = remember(item, installed) { providerApps(item.providers, installed) }
    // "Paramount Plus Premium", "Paramount+ Amazon Channel"… → just the service names.
    val missing = item.providers
        // A provider counts as covered if any installed app plays it (e.g. "Netflix Standard with Ads").
        .filter { p -> providerApps(listOf(p), installed).isEmpty() && !p.contains("channel", ignoreCase = true) }
        .map { it.replace(Regex("(?i)\\s+(with ads|standard with ads|premium|basic( with ads)?|essential|standard)$"), "") }
        .distinct().take(3)
    ZenDialog(item.title, onDismiss, width = 680.dp, initialFocus = first) {
        Txt(metaLine(item), Modifier.padding(horizontal = 18.dp), size = 15.sp, color = Zen.TextDim)
        if (status != null) Txt(status, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), size = 15.sp, color = Zen.Accent, maxLines = 4)
        if (item.overview.isNotEmpty()) {
            Txt(item.overview, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), size = 16.sp, maxLines = 7)
        }
        onApps.forEachIndexed { i, (provider, app) ->
            ZenListItem("Open ${app.label}", { onOpenApp(app) },
                if (i == 0) Modifier.focusRequester(first) else Modifier, detail = "Streaming on $provider")
        }
        when {
            missing.isNotEmpty() -> Note(
                "Also on ${missing.joinToString(", ")} (not installed)",
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = Zen.TextFaint,
            )
            onApps.isEmpty() -> Note(
                if (region == null) "Set a weather city to see where it's streaming."
                else "Not on a subscription service in $region yet.",
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = Zen.TextFaint,
            )
        }
        val firstIfNoApp = if (onApps.isEmpty()) Modifier.focusRequester(first) else Modifier
        if (inWatchlist) {
            // Already saved: Close comes before Remove, so a habitual double-OK can't delete it.
            ZenListItem("Close", onDismiss, firstIfNoApp)
            ZenListItem("Remove from watchlist", onToggleWatchlist)
        } else {
            ZenListItem(
                "Add to watchlist", onToggleWatchlist, firstIfNoApp,
                detail = "DC Launcher checks daily and tells you when it's on your apps",
            )
            if (onNotInterested != null) {
                ZenListItem("Not interested", onNotInterested, detail = "Never show it in the spotlight again")
            }
            ZenListItem("Close", onDismiss)
        }
        Txt("Data and images from TMDB", Modifier.padding(start = 18.dp, top = 6.dp), size = 12.sp, color = Zen.TextFaint)
    }
}

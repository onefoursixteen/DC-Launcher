package me.cazan.zen.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import me.cazan.zen.data.SpotlightRepository
import me.cazan.zen.data.WatchItem
import me.cazan.zen.data.WatchLabel

private val Good = Color(0xFF7FD49B)
private val TitleScrim = Brush.verticalGradient(0.45f to Color(0x000E1116), 1f to Color(0xE60E1116))

/** A watchlist title: artwork card with its name, and today's status underneath. */
@Composable
fun WatchTile(
    item: WatchItem,
    label: WatchLabel,
    repo: SpotlightRepository,
    wantsFocus: Boolean,
    onFocusTaken: () -> Unit,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    externalRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    rowEnd: Boolean = false,
) {
    val requester = externalRequester ?: remember { FocusRequester() }
    val press = remember { PressTracker() }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "watchScale")
    var art by remember(item.backdrop) { mutableStateOf(item.backdrop?.let(repo::cachedThumb)) }
    LaunchedEffect(item.backdrop) { if (art == null) item.backdrop?.let { art = repo.thumb(it) } }
    LaunchedEffect(wantsFocus) {
        if (wantsFocus) {
            runCatching { requester.requestFocus() }
            onFocusTaken()
        }
    }

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .onKeyEvent { ev ->
                    val native = ev.nativeKeyEvent
                    when {
                        isSelectKey(native.keyCode) -> {
                            if (ev.type == KeyEventType.KeyDown) {
                                if (native.repeatCount == 0) {
                                    press.down = true
                                    press.longFired = false
                                    press.downAt = android.os.SystemClock.uptimeMillis()
                                } else if (press.down && !press.longFired) {
                                    press.longFired = true
                                    onMenu()
                                }
                            } else if (ev.type == KeyEventType.KeyUp) {
                                if (press.down && !press.longFired) {
                                    if (press.heldLong(native)) onMenu() else onClick()
                                }
                                press.down = false
                            }
                            true
                        }
                        native.keyCode == NativeKeyEvent.KEYCODE_MENU -> {
                            if (ev.type == KeyEventType.KeyUp) onMenu()
                            true
                        }
                        else -> false
                    }
                }
                .focusProperties {
                    if (upTarget != null) up = upTarget
                    if (rowEnd) right = FocusRequester.Cancel
                }
                .focusable()
                .pointerInput(item.key) { detectTapGestures(onTap = { onClick() }, onLongPress = { onMenu() }) }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shape = Zen.Card
                    clip = true
                    shadowElevation = if (focused) 20.dp.toPx() else 0f
                    ambientShadowColor = Zen.Accent
                    spotShadowColor = Zen.Accent
                }
                .then(if (focused) Modifier.border(3.dp, Zen.Focus, Zen.Card) else Modifier)
                .background(Color(0xFF1B1F27)),
        ) {
            art?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(TitleScrim))
            Txt(
                item.title,
                Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp),
                size = 17.sp, weight = FontWeight.Medium, maxLines = 2,
            )
            if (label.promote) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Good, Zen.Pill)
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) { Txt("NEW", size = 11.sp, color = Zen.OnFocus, weight = FontWeight.Bold) }
            }
        }
        Txt(
            label.text,
            Modifier.fillMaxWidth().padding(top = 12.dp, start = 2.dp),
            size = 14.sp,
            color = when {
                label.watchable -> Good
                focused -> Zen.Text
                else -> Zen.TextDim
            },
            weight = if (label.watchable) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun WatchOptionsDialog(
    item: WatchItem,
    label: WatchLabel,
    detail: String?,
    onOpenApp: () -> Unit,
    onDetails: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    ZenDialog(item.title, onDismiss, initialFocus = first) {
        Note(label.text, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), color = if (label.watchable) Good else Zen.TextDim)
        detail?.takeIf { it != label.text }?.let { Note(it, Modifier.padding(horizontal = 18.dp), color = Zen.TextDim) }
        val app = label.app
        if (app != null) ZenListItem("Open ${app.label}", onOpenApp, Modifier.focusRequester(first))
        ZenListItem("Details", onDetails, if (app == null) Modifier.focusRequester(first) else Modifier)
        ZenListItem("Remove from watchlist", onRemove)
    }
}

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.cazan.zen.data.AppEntry
import me.cazan.zen.data.AppRepository

private const val FOCUSED_SCALE = 1.1f

/** Tracks whether the select key went down on *this* tile, so a stray key-up never launches. */
internal class PressTracker {
    var down = false
    var longFired = false
    /** When we received key-down, by our own clock: some sources stamp key-up with the down time. */
    var downAt = 0L
}

/**
 * Some inputs (the emulator's D-pad, some IR remotes and keyboards) send only key-down and
 * key-up with no auto-repeat in between, so a long press must also be recognised by how long
 * the key was held when it's released.
 */
@Suppress("UNUSED_PARAMETER")
internal fun PressTracker.heldLong(up: NativeKeyEvent) =
    android.os.SystemClock.uptimeMillis() - downAt >= android.view.ViewConfiguration.getLongPressTimeout()

internal fun isSelectKey(code: Int) = code == NativeKeyEvent.KEYCODE_DPAD_CENTER ||
    code == NativeKeyEvent.KEYCODE_ENTER ||
    code == NativeKeyEvent.KEYCODE_NUMPAD_ENTER ||
    code == NativeKeyEvent.KEYCODE_BUTTON_A

/**
 * One app in the grid. Focus engine rules:
 *  - OK (short press)   → launch
 *  - OK (long press) / MENU → options
 *  - In move mode, arrows reorder instead of moving focus; OK drops.
 * Focus visuals are drawn in graphicsLayer (scale + coloured shadow) so they never trigger
 * a re-layout of the grid, which keeps D-pad scrolling smooth on weak TV SoCs.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(
    entry: AppEntry,
    repo: AppRepository,
    index: Int,
    columns: Int,
    showLabel: Boolean,
    favoriteMark: Boolean,
    moving: Boolean,
    wantsFocus: Boolean,
    onFocusTaken: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onMove: (delta: Int) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
    externalRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    rowEnd: Boolean = false,
    onFocused: () -> Unit = {},
) {
    val requester = externalRequester ?: remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    val press = remember { PressTracker() }
    var focused by remember { mutableStateOf(false) }
    val highlighted = focused || moving
    val scale by animateFloatAsState(if (highlighted) FOCUSED_SCALE else 1f, tween(140), label = "tileScale")
    val art by produceState(repo.cachedArt(entry.key), entry.key) {
        if (value == null) value = repo.art(entry)
    }

    LaunchedEffect(wantsFocus) {
        if (wantsFocus) {
            runCatching { requester.requestFocus() }
            onFocusTaken()
        }
    }
    LaunchedEffect(moving, index) {
        if (moving) {
            runCatching { requester.requestFocus() }
            bring.bringIntoView()
        }
    }

    fun handleMoveKey(ev: KeyEvent): Boolean {
        val native = ev.nativeKeyEvent
        val down = ev.type == KeyEventType.KeyDown
        val delta = when (native.keyCode) {
            NativeKeyEvent.KEYCODE_DPAD_LEFT -> -1
            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> 1
            NativeKeyEvent.KEYCODE_DPAD_UP -> -columns
            NativeKeyEvent.KEYCODE_DPAD_DOWN -> columns
            else -> 0
        }
        if (delta != 0) {
            if (down) onMove(delta)
            return true // swallow so focus doesn't travel while reordering
        }
        if (isSelectKey(native.keyCode)) {
            if (down && native.repeatCount == 0) press.down = true
            if (!down && press.down) {
                press.down = false
                onDrop()
            }
            return true
        }
        return false
    }

    fun handleKey(ev: KeyEvent): Boolean {
        val native = ev.nativeKeyEvent
        return when {
            isSelectKey(native.keyCode) -> {
                if (ev.type == KeyEventType.KeyDown) {
                    if (native.repeatCount == 0) {
                        press.down = true
                        press.longFired = false
                        press.downAt = android.os.SystemClock.uptimeMillis()
                    } else if (press.down && !press.longFired && (native.isLongPress || native.repeatCount >= 1)) {
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

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .bringIntoViewRequester(bring)
                .focusRequester(requester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .onPreviewKeyEvent { if (moving) handleMoveKey(it) else false }
                .onKeyEvent(::handleKey)
                .focusProperties {
                    if (upTarget != null) up = upTarget
                    if (rowEnd) right = FocusRequester.Cancel
                }
                .focusable()
                .pointerInput(entry.key) {
                    detectTapGestures(onTap = { onClick() }, onLongPress = { onMenu() })
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shape = Zen.Card
                    clip = true
                    shadowElevation = if (highlighted) 20.dp.toPx() else 0f
                    ambientShadowColor = Zen.Accent
                    spotShadowColor = Zen.Accent
                }
                .then(
                    if (highlighted) Modifier.border(3.dp, if (moving) Zen.Accent else Zen.Focus, Zen.Card)
                    else Modifier
                )
                .background(tileColor(entry)),
            contentAlignment = Alignment.Center,
        ) {
            val a = art
            when {
                a == null -> Txt(
                    entry.label, Modifier.padding(12.dp), size = 16.sp,
                    weight = FontWeight.Medium, maxLines = 2, align = TextAlign.Center,
                )
                a.isBanner -> Image(a.bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Image(a.bitmap, null, Modifier.fillMaxHeight(0.55f).aspectRatio(1f))
            }
            if (moving) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .background(Color(0xCC0E1116), Zen.Pill)
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Txt("◀ ▲ ▼ ▶", size = 13.sp, color = Zen.Accent, weight = FontWeight.Medium)
                }
            }
            if (favoriteMark) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(8.dp)
                        .background(Zen.Accent, CircleShape)
                )
            }
        }
        Txt(
            entry.label,
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .alpha(if (showLabel || highlighted) 1f else 0f),
            size = 15.sp,
            color = if (highlighted) Zen.Text else Zen.TextDim,
            weight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
            align = TextAlign.Center,
        )
    }
}

/** Stable muted backdrop per app for icon-only (phone/sideloaded) tiles. */
private fun tileColor(entry: AppEntry): Color {
    val hue = ((entry.packageName.hashCode() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.35f, 0.26f)
}

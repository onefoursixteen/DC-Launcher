package me.cazan.zen.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

/** One corner radius for the panel, the focus highlight and the app icons, so all curves match. */
private val DockCorner = 14.dp
private val DockShape = RoundedCornerShape(DockCorner)
private const val MaxVisible = 6
private val SidePad = 24.dp
private val Gap = 6.dp

/** Fades the first and last few percent of the row to transparent, so scrolled-off apps don't look cut. */
private fun Modifier.fadeEdges(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.05f to Color.Black, 0.95f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * The "Dock" home layout: one row of app banners on a frosted-glass panel. LEFT/RIGHT
 * moves along the row (scrolling if there are more apps than fit), OK opens, hold OK or MENU
 * shows the app options, and in move mode LEFT/RIGHT reorders.
 */
@Composable
fun DockRow(
    apps: List<AppEntry>,
    repo: AppRepository,
    movingKey: String?,
    focusKey: String?,
    onFocusTaken: () -> Unit,
    onOpen: (AppEntry) -> Unit,
    onMenu: (AppEntry) -> Unit,
    onMove: (Int) -> Unit,
    onDrop: () -> Unit,
    firstRequester: FocusRequester,
    upTarget: FocusRequester?,
    modifier: Modifier = Modifier,
    /** Bumped by the home screen when it wants the row back at its first app (start-up, Home). */
    scrollReset: Int = 0,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollReset) { if (apps.isNotEmpty()) listState.scrollToItem(0) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Items are always sized for six slots, so a dock with one or two apps keeps normal-sized
        // icons and the glass panel shrinks around them, centred. Beyond six the row scrolls.
        val inner = maxWidth - SidePad * 2
        val itemWidth = (inner - Gap * (MaxVisible - 1)) / MaxVisible
        val scrolls = apps.size > MaxVisible
        val shown = apps.size.coerceIn(1, MaxVisible)
        val panelWidth = if (scrolls) maxWidth else itemWidth * shown + Gap * (shown - 1) + SidePad * 2
        Box(
            Modifier
                .width(panelWidth)
                .glassPanel()
                .padding(vertical = 4.dp),
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scrolls) Modifier.fadeEdges() else Modifier),
                // Vertical room so the focused item (scaled and lifted) isn't clipped flat.
                contentPadding = PaddingValues(horizontal = SidePad, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(Gap),
            ) {
                itemsIndexed(apps, key = { _, e -> e.key }) { index, entry ->
                    DockItem(
                        entry = entry,
                        repo = repo,
                        moving = movingKey == entry.key,
                        wantsFocus = focusKey == entry.key,
                        onFocusTaken = onFocusTaken,
                        onOpen = { onOpen(entry) },
                        onMenu = { onMenu(entry) },
                        onMove = onMove,
                        onDrop = onDrop,
                        requester = if (index == 0) firstRequester else null,
                        upTarget = upTarget,
                        lastInRow = index == apps.lastIndex,
                        index = index,
                        width = itemWidth,
                    )
                }
            }
        }
    }
}

/**
 * Raised glass: a soft shadow pooled under the panel, a top-lit gradient fill, a sheen across
 * the upper half and a rim that is bright along the top edge and fades towards the bottom.
 */
private fun Modifier.glassPanel(): Modifier = this
    .drawBehind {
        // Only below the panel: anything drawn inside would show through the glass as a dark band.
        val drop = 22.dp.toPx()
        val inset = DockCorner.toPx()
        drawRoundRect(
            Brush.verticalGradient(
                0f to Color(0x66000000), 1f to Color.Transparent,
                startY = size.height, endY = size.height + drop,
            ),
            topLeft = Offset(inset, size.height),
            size = Size(size.width - inset * 2, drop),
            cornerRadius = CornerRadius(drop / 2),
        )
    }
    .clip(DockShape)
    .background(Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x17FFFFFF), Color(0x0FFFFFFF))))
    .drawBehind {
        drawRect(
            Brush.verticalGradient(0f to Color(0x24FFFFFF), 0.5f to Color.Transparent),
            size = Size(size.width, size.height * 0.5f),
        )
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x26FFFFFF), Color(0x40FFFFFF))), DockShape)

@Composable
private fun DockItem(
    entry: AppEntry,
    repo: AppRepository,
    moving: Boolean,
    wantsFocus: Boolean,
    onFocusTaken: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onMove: (Int) -> Unit,
    onDrop: () -> Unit,
    requester: FocusRequester?,
    upTarget: FocusRequester?,
    lastInRow: Boolean,
    index: Int,
    width: Dp,
) {
    val focusRequester = requester ?: remember { FocusRequester() }
    val press = remember { PressTracker() }
    var focused by remember { mutableStateOf(false) }
    val lit = focused || moving
    val scale by animateFloatAsState(if (lit) 1.1f else 1f, tween(160), label = "dockScale")
    val lift by animateDpAsState(if (lit) 6.dp else 0.dp, tween(160), label = "dockLift")
    val depth by animateDpAsState(if (lit) 14.dp else 3.dp, tween(160), label = "dockDepth")
    val logoAlpha by animateFloatAsState(if (lit) 1f else 0.9f, tween(140), label = "dockAlpha")

    var art by remember(entry.key) { mutableStateOf(repo.cachedArt(entry.key)) }
    LaunchedEffect(entry.key) { if (art == null) art = repo.art(entry) }
    LaunchedEffect(wantsFocus) {
        if (wantsFocus) { runCatching { focusRequester.requestFocus() }; onFocusTaken() }
    }
    LaunchedEffect(moving, index) { if (moving) runCatching { focusRequester.requestFocus() } }

    Column(
        Modifier
            .width(width)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (!moving) return@onPreviewKeyEvent false
                val native = ev.nativeKeyEvent
                val down = ev.type == KeyEventType.KeyDown
                when (native.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_LEFT -> { if (down) onMove(-1); true }
                    NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) onMove(1); true }
                    NativeKeyEvent.KEYCODE_DPAD_UP, NativeKeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> if (isSelectKey(native.keyCode)) {
                        if (down && native.repeatCount == 0) press.down = true
                        if (!down && press.down) { press.down = false; onDrop() }
                        true
                    } else false
                }
            }
            .onKeyEvent { ev ->
                val native = ev.nativeKeyEvent
                when {
                    isSelectKey(native.keyCode) -> {
                        if (ev.type == KeyEventType.KeyDown) {
                            if (native.repeatCount == 0) {
                                press.down = true; press.longFired = false
                                press.downAt = android.os.SystemClock.uptimeMillis()
                            } else if (press.down && !press.longFired) {
                                press.longFired = true; onMenu()
                            }
                        } else if (ev.type == KeyEventType.KeyUp) {
                            if (press.down && !press.longFired) { if (press.heldLong()) onMenu() else onOpen() }
                            press.down = false
                        }
                        true
                    }
                    native.keyCode == NativeKeyEvent.KEYCODE_MENU -> { if (ev.type == KeyEventType.KeyUp) onMenu(); true }
                    else -> false
                }
            }
            .focusProperties {
                if (upTarget != null) up = upTarget
                if (lastInRow) right = FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .focusable()
            .pointerInput(entry.key) { detectTapGestures(onTap = { onOpen() }, onLongPress = { onMenu() }) }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = -lift.toPx() }
            .clip(DockShape)
            .then(
                when {
                    moving -> Modifier
                        .background(Color(0x408AB4FF))
                        .border(1.dp, Color(0x998AB4FF), DockShape)
                    focused -> Modifier
                        .background(Brush.verticalGradient(listOf(Color(0x47FFFFFF), Color(0x14FFFFFF))))
                        .border(1.dp, Brush.verticalGradient(listOf(Color(0xB3FFFFFF), Color(0x1AFFFFFF))), DockShape)
                    else -> Modifier
                }
            )
            .padding(vertical = 10.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own banner in full colour; phone-style apps show their icon on a tinted card.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(depth, DockShape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(Color(0x40000000))
                .graphicsLayer { alpha = logoAlpha }
                // Glossy highlight across the top of the banner, stronger when focused.
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(0f to Color.White.copy(alpha = if (lit) 0.22f else 0.10f), 0.5f to Color.Transparent),
                        size = Size(size.width, size.height * 0.5f),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val a = art
            when {
                a == null -> Txt(entry.label, size = 16.sp, weight = FontWeight.Medium, color = Zen.Text, align = TextAlign.Center)
                a.isBanner -> Image(a.bitmap, entry.label, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                else -> Image(a.bitmap, entry.label, Modifier.fillMaxHeight(0.6f).aspectRatio(1f))
            }
        }
        Txt(
            if (moving) "◀ ▶" else entry.label,
            Modifier.padding(top = 8.dp).crawlWhen(focused && !moving),
            size = 14.sp,
            color = if (lit) Zen.Text else Zen.TextDim,
            weight = if (lit) FontWeight.Medium else FontWeight.Normal,
            align = TextAlign.Center,
        )
    }
}

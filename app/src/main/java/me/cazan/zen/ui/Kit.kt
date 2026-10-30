package me.cazan.zen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A minimal design kit on top of Compose foundation. Skipping Material/TV-Material keeps the
 * APK small and avoids their theming machinery at startup. Every focusable element follows
 * one rule: focused = inverted (light fill, dark text) + slight scale, so the focus is
 * obvious from across the room.
 */
object Zen {
    val Accent = Color(0xFF8AB4FF)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xA6F2F4F8)
    val TextFaint = Color(0x66F2F4F8)
    val Surface = Color(0x1AFFFFFF)
    val SurfaceSelected = Color(0x33FFFFFF)
    val Focus = Color(0xFFF2F4F8)
    val OnFocus = Color(0xFF0E1116)
    val Panel = Color(0xFF181C24)
    val Background = Color(0xFF0E1116)
    val Pill = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(12.dp)
    val Row = RoundedCornerShape(10.dp)
}

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = Zen.Text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Txt(text, modifier.padding(top = 8.dp), size = 22.sp, weight = FontWeight.Medium)

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Txt(text.uppercase(), modifier.padding(top = 4.dp), size = 12.sp, color = Zen.TextDim, weight = FontWeight.Medium)

@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = Zen.TextDim) =
    Txt(text, modifier, size = 15.sp, color = color, maxLines = 4)

@Composable
private fun rememberFocusScale(focused: Boolean, to: Float = 1.06f): Float {
    val scale by animateFloatAsState(if (focused) to else 1f, tween(120), label = "focusScale")
    return scale
}

@Composable
fun ZenButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale = rememberFocusScale(focused)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Zen.Pill)
            .background(
                when {
                    focused -> Zen.Focus
                    selected -> Zen.SurfaceSelected
                    else -> Zen.Surface
                }
            )
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text,
            color = when {
                focused -> Zen.OnFocus
                enabled -> Zen.Text
                else -> Zen.TextFaint
            },
            weight = FontWeight.Medium,
        )
    }
}

@Composable
fun ZenIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale = rememberFocusScale(focused, 1.12f)
    Box(
        modifier
            .size(48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (focused) Zen.Focus else Zen.Surface)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(if (focused) Zen.OnFocus else Zen.Text)
    }
}

/** Full-width row used in menus and settings lists. */
@Composable
fun ZenListItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: (@Composable (focused: Boolean) -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale = rememberFocusScale(focused, 1.02f)
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Zen.Row)
            .background(if (focused) Zen.Focus else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(text, size = 18.sp, color = if (focused) Zen.OnFocus else Zen.Text)
            if (detail != null) {
                Txt(detail, size = 13.sp, color = if (focused) Zen.OnFocus.copy(alpha = 0.7f) else Zen.TextDim)
            }
        }
        trailing?.invoke(focused)
    }
}

@Composable
fun ToggleRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) = ZenListItem(text, onToggle, modifier, detail) { focused -> SwitchGlyph(checked, focused) }

@Composable
fun ValueRow(text: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    ZenListItem(text, onClick, modifier) { focused ->
        Txt(value, size = 16.sp, color = if (focused) Zen.OnFocus else Zen.Accent)
    }

@Composable
fun SwitchGlyph(checked: Boolean, focused: Boolean) {
    val track = when {
        checked -> Zen.Accent
        focused -> Color(0x33000000)
        else -> Color(0x33FFFFFF)
    }
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(Zen.Pill)
            .background(track)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) Zen.OnFocus else Zen.Text))
    }
}

/**
 * Modal panel. [initialFocus] is requested once the dialog window is attached, so the
 * remote lands on the first option instead of nowhere.
 */
@Composable
fun ZenDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 480.dp,
    initialFocus: FocusRequester? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A dialog opened by holding OK keeps receiving that same held key (auto-repeat, then
        // the release). Ignore OK until it's pressed fresh inside the dialog, or releasing the
        // long press would "click" the first item.
        val armed = remember { booleanArrayOf(false) }
        Column(
            Modifier
                .onPreviewKeyEvent { ev ->
                    val native = ev.nativeKeyEvent
                    if (!isSelectKey(native.keyCode) || armed[0]) return@onPreviewKeyEvent false
                    if (ev.type == KeyEventType.KeyDown && native.repeatCount == 0) {
                        armed[0] = true
                        false
                    } else {
                        true // swallow the leftover repeats and release of the earlier press
                    }
                }
                .width(width)
                .clip(RoundedCornerShape(20.dp))
                .background(Zen.Panel)
                .padding(24.dp),
        ) {
            Txt(title, Modifier.padding(start = 18.dp, bottom = 14.dp), size = 22.sp, weight = FontWeight.Medium)
            Column(
                (if (scrollable) Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
        }
        if (initialFocus != null) {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { initialFocus.requestFocus() }
            }
        }
    }
}

/** Text entry in its own dialog, so the on-screen keyboard only opens when the user asks. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    ZenDialog(title, onDismiss, width = 620.dp, initialFocus = field) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = Zen.Text, fontSize = 20.sp),
            cursorBrush = SolidColor(Zen.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(field)
                .onFocusChanged { focused = it.isFocused }
                .clip(Zen.Row)
                .background(Zen.Surface)
                .border(2.dp, if (focused) Zen.Accent else Color.Transparent, Zen.Row)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) Txt(hint, size = 20.sp, color = Zen.TextFaint)
                    inner()
                }
            },
        )
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZenButton("Save", onClick = { onDone(value.text) })
            ZenButton("Cancel", onClick = onDismiss)
        }
    }
}

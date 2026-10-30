package me.cazan.zen.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

/** Space under each tile for its name (padding + one line of 15sp text). */
private val LabelSpace = 36.dp
/** Room above and beside tiles for the 1.1x focus scale and glow. */
private val FocusRoom = 12.dp
private val ColumnGap = 26.dp

/** First visible row so that [focusedRow] is inside a [visibleRows]-row window starting at [top]. */
fun windowTopFor(focusedRow: Int, top: Int, visibleRows: Int = 2): Int = when {
    focusedRow < top -> focusedRow
    focusedRow > top + visibleRows - 1 -> focusedRow - visibleRows + 1
    else -> top
}

/**
 * A fixed window showing exactly two full rows of tiles. Moving focus past the last visible
 * row slides the rows up by one (and back down), so the rest of the home screen never moves
 * and a half-cut row never peeks in. Tiles shrink if needed so two rows always fit.
 *
 * All rows are composed (TV app lists are small), which keeps D-pad focus search simple:
 * the row above or below always exists to move into.
 */
@Composable
fun RowWindow(
    count: Int,
    columns: Int,
    topRow: Int,
    modifier: Modifier = Modifier,
    tileAspect: Float = 16f / 9f,
    item: @Composable (index: Int, tileWidth: Dp) -> Unit,
) {
    val rows = (count + columns - 1) / columns
    BoxWithConstraints(modifier) {
        val rowHeight = maxHeight / 2
        val cellWidth = (maxWidth - FocusRoom * 2 - ColumnGap * (columns - 1)) / columns
        val tileWidth = min(cellWidth, (rowHeight - FocusRoom - LabelSpace) * tileAspect)
        val slide by animateDpAsState(rowHeight * topRow, tween(280), label = "rowSlide")

        // Unbounded height: every row keeps its full height instead of being squeezed into the window.
        // No clipping (it would cut the focus glow with hard edges): rows outside the window
        // fade out instead, and the glow can spill past the window like everywhere else.
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .offset { IntOffset(0, -slide.roundToPx()) }
        ) {
            for (r in 0 until rows) {
                val inWindow = r == topRow || r == topRow + 1
                val rowAlpha by animateFloatAsState(if (inWindow) 1f else 0f, tween(220), label = "rowAlpha")
                Row(
                    Modifier.fillMaxWidth().height(rowHeight).graphicsLayer { alpha = rowAlpha }.padding(horizontal = FocusRoom).padding(top = FocusRoom),
                    horizontalArrangement = Arrangement.spacedBy(ColumnGap),
                ) {
                    for (c in 0 until columns) {
                        val i = r * columns + c
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            if (i < count) item(i, tileWidth)
                        }
                    }
                }
            }
        }

        // Which rows are showing: a slim bar per row on the right edge.
        if (rows > 2) {
            Column(
                // Sits in the screen's side margin, clear of the last column.
                Modifier.align(Alignment.CenterEnd).offset(x = 22.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (topRow > 0) Txt("⌃", size = 14.sp, color = Zen.TextDim)
                for (r in 0 until rows) {
                    val shown = r == topRow || r == topRow + 1
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(if (shown) 14.dp else 6.dp)
                            .background(if (shown) Zen.Text else Zen.TextFaint, Zen.Pill)
                    )
                }
                if (topRow + 2 < rows) Txt("⌄", size = 14.sp, color = Zen.TextDim)
            }
        }
    }
}

/** Spacer helper so callers can keep their imports small. */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.size(width = 0.dp, height = height))

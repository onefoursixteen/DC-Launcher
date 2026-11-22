package me.cazan.zen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.cazan.zen.data.SeerrResult
import me.cazan.zen.data.SeerrState
import me.cazan.zen.data.SeerrTitle
import me.cazan.zen.data.SpotlightRepository

/** Short badge for tiles and search results; empty when Seerr knows nothing about the title. */
fun SeerrState.badge(): String = when (this) {
    SeerrState.NONE -> ""
    SeerrState.PENDING -> "Requested"
    SeerrState.PROCESSING -> "Downloading"
    SeerrState.PARTIAL -> "Partly in library"
    SeerrState.AVAILABLE -> "In library"
    SeerrState.DECLINED -> "Declined"
}

/** Search results from Seerr as a full-screen poster grid. OK on a poster opens its details. */
@Composable
fun SeerrSearchScreen(
    query: String,
    results: List<SeerrResult>?,
    error: String?,
    images: SpotlightRepository,
    onOpen: (SeerrResult) -> Unit,
    onNewSearch: () -> Unit,
    onClose: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val newSearch = remember { FocusRequester() }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Column(
            Modifier
                .fillMaxSize()
                .background(Zen.Background)
                .padding(horizontal = 48.dp, vertical = 27.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt("Search", size = 30.sp, weight = FontWeight.Medium)
                    Txt(
                        when {
                            error != null -> error
                            results == null -> "Searching for “$query”…"
                            results.isEmpty() -> "Nothing found for “$query”"
                            else -> "${results.size} results for “$query” · OK for details and requests"
                        },
                        size = 15.sp, color = if (error != null) Zen.Accent else Zen.TextDim,
                    )
                }
                ZenButton("New search", onNewSearch, Modifier.focusRequester(newSearch))
                Spacer(Modifier.padding(start = 12.dp))
                ZenButton("Close", onClose)
            }
            Spacer(Modifier.height(18.dp))
            if (!results.isNullOrEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(results, key = { _, r -> r.key }) { i, r ->
                        PosterCard(r, images, { onOpen(r) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                    }
                }
            }
        }
        LaunchedEffect(results, error) {
            // Land on the first poster when there are results, otherwise on "New search".
            repeat(10) {
                androidx.compose.runtime.withFrameNanos { }
                val ok = runCatching { (if (!results.isNullOrEmpty()) first else newSearch).requestFocus() }.getOrDefault(false)
                if (ok) return@LaunchedEffect
            }
        }
    }
}

@Composable
private fun PosterCard(r: SeerrResult, images: SpotlightRepository, onClick: () -> Unit, modifier: Modifier) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale = rememberFocusScale(focused, 1.06f)
    var art by remember(r.poster) { mutableStateOf(r.poster?.let(images::cachedPoster)) }
    LaunchedEffect(r.poster) { if (art == null) art = r.poster?.let { images.poster(it) } }
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(Zen.Card)
                .background(Zen.Surface)
                .border(3.dp, if (focused) Zen.Focus else Color.Transparent, Zen.Card),
            contentAlignment = Alignment.Center,
        ) {
            val a = art
            if (a != null) Image(a, r.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Txt(r.title, Modifier.padding(10.dp), size = 15.sp, align = TextAlign.Center)
            val badge = r.state.badge()
            if (badge.isNotEmpty()) {
                Txt(
                    badge,
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(Zen.Pill)
                        .background(if (r.state == SeerrState.AVAILABLE) Color(0xE62E7D4F) else Color(0xE6222833))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    size = 12.sp, weight = FontWeight.Medium,
                )
            }
        }
        Txt(r.title, Modifier.padding(top = 8.dp), size = 15.sp, color = if (focused) Zen.Text else Zen.TextDim, maxLines = 1)
        Txt(
            listOf(if (r.type == "movie") "Movie" else "Series", r.year).filter { it.isNotEmpty() }.joinToString(" · "),
            size = 13.sp, color = Zen.TextFaint, maxLines = 1,
        )
    }
}

/** Pick which seasons of a series to request. Seasons already in the library or requested can't be picked. */
@Composable
fun SeasonPickerDialog(title: SeerrTitle, name: String, onRequest: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    val missing = title.seasons.filter { it.state == SeerrState.NONE || it.state == SeerrState.DECLINED }.map { it.number }
    var picked by remember(title) { mutableStateOf(missing.toSet()) }
    val first = remember { FocusRequester() }
    ZenDialog("Request · $name", onDismiss, width = 600.dp, initialFocus = first) {
        if (missing.isEmpty()) {
            Note("Every season is already in your library or requested.", Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            ZenListItem("Close", onDismiss, Modifier.focusRequester(first))
            return@ZenDialog
        }
        ZenListItem(
            if (picked.isEmpty()) "Pick at least one season" else
                "Request ${if (picked.size == missing.size && missing.size > 1) "all ${picked.size} missing seasons" else picked.sorted().joinToString(", ", "season${if (picked.size > 1) "s" else ""} ")}",
            { if (picked.isNotEmpty()) onRequest(picked.sorted()) },
            Modifier.focusRequester(first),
            detail = "Requested as your Jellyfin user",
        )
        if (missing.size > 1) {
            ZenListItem("Only the latest missing season (${missing.max()})", { onRequest(listOf(missing.max())) })
        }
        title.seasons.forEach { s ->
            val open = s.number in missing
            ToggleRow(
                s.name,
                checked = if (open) s.number in picked else true,
                onToggle = { if (open) picked = if (s.number in picked) picked - s.number else picked + s.number },
                detail = listOfNotNull(
                    s.episodes.takeIf { it > 0 }?.let { "$it episodes" },
                    s.state.badge().takeIf { !open && it.isNotEmpty() },
                ).joinToString(" · ").ifEmpty { null },
            )
        }
        ZenListItem("Cancel", onDismiss)
    }
}

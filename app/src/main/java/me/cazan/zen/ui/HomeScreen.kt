package me.cazan.zen.ui

import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.cazan.zen.ZenApp
import me.cazan.zen.data.AppEntry
import me.cazan.zen.data.ClockFormat
import me.cazan.zen.data.Config
import me.cazan.zen.data.NetState
import me.cazan.zen.data.SpotlightItem
import me.cazan.zen.data.WatchItem
import me.cazan.zen.data.WatchStatus
import me.cazan.zen.data.providerApps
import me.cazan.zen.data.toSpotlight
import me.cazan.zen.data.toWatchItem
import me.cazan.zen.data.watchDetail
import me.cazan.zen.data.watchLabel
import me.cazan.zen.data.WeatherNow
import me.cazan.zen.data.weatherLabel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val TAB_FAVORITES = "fav"
private const val TAB_ALL = "all"
private const val TAB_WATCH = "watch"
private const val SPOTLIGHT_ROTATE_MS = 12_000L
private val SPOTLIGHT_HEIGHT = 156.dp

private data class TabSpec(val id: String, val title: String)

class HomeActions(
    val launch: (AppEntry) -> Unit,
    val openSettings: () -> Unit,
    val openSystemSettings: () -> Unit,
    val openSettingsPage: (action: String) -> Unit,
    val appInfo: (AppEntry) -> Unit,
    val uninstall: (AppEntry) -> Unit,
)

/** Global display order: user order first, then everything else alphabetically (input is pre-sorted). */
fun orderApps(apps: List<AppEntry>, order: List<String>): List<AppEntry> {
    if (order.isEmpty()) return apps
    val rank = HashMap<String, Int>(order.size * 2)
    order.forEachIndexed { i, key -> rank[key] = i }
    return apps.sortedBy { rank[it.key] ?: Int.MAX_VALUE } // stable: unranked stay alphabetical
}

private fun appsForTab(shown: List<AppEntry>, config: Config, tabId: String): List<AppEntry> = when (tabId) {
    TAB_FAVORITES -> shown.filter { it.key in config.favorites }
    TAB_ALL -> shown
    else -> {
        val members = config.categories.firstOrNull { it.id == tabId }?.apps?.toHashSet() ?: emptySet()
        shown.filter { it.key in members }
    }
}

private fun WatchItem.asSpotlight(s: WatchStatus?): SpotlightItem =
    s?.let { toSpotlight(it, "On your watchlist") }
        ?: SpotlightItem(id, type, title, overview, year, emptyList(), 0.0, backdrop.orEmpty(), null, emptyList(), "On your watchlist")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(app: ZenApp, homeSignal: Int, visible: Boolean, actions: HomeActions) {
    val prefs = app.prefs
    val config by prefs.config.collectAsState()
    val allApps by app.apps.apps.collectAsState()
    val weather by app.weather.now.collectAsState()
    val net by app.network.status.collectAsState()
    var showNetwork by remember { mutableStateOf(false) }
    val spotItems by app.spotlight.items.collectAsState()
    val watchStatus by app.watchlist.status.collectAsState()

    val ordered = remember(allApps, config.order) { orderApps(allApps, config.order) }
    val shown = remember(ordered, config.hidden) { ordered.filterNot { it.key in config.hidden } }
    val hasFavorites = config.favorites.any { key -> shown.any { it.key == key } }
    val hasWatchlist = config.watchlist.isNotEmpty() && config.tmdbKey != null
    val tabs = remember(hasFavorites, hasWatchlist, config.categories) {
        buildList {
            if (hasFavorites) add(TabSpec(TAB_FAVORITES, "Favorites"))
            if (hasWatchlist) add(TabSpec(TAB_WATCH, "Watchlist"))
            add(TabSpec(TAB_ALL, "All apps"))
            config.categories.forEach { add(TabSpec(it.id, it.name)) }
        }
    }
    val defaultTab = if (hasFavorites) TAB_FAVORITES else TAB_ALL
    var selectedTab by rememberSaveable { mutableStateOf(defaultTab) }
    val tabId = if (tabs.any { it.id == selectedTab }) selectedTab else TAB_ALL
    LaunchedEffect(tabId) { if (selectedTab != tabId) selectedTab = tabId }

    val tabApps = remember(shown, tabId, config.favorites, config.categories) { appsForTab(shown, config, tabId) }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var categoriesFor by remember { mutableStateOf<AppEntry?>(null) }
    var movingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusKey by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf(true) }
    val firstTile = remember { FocusRequester() }
    val tabRequesters = remember { HashMap<String, FocusRequester>() }
    val selectedTabRequester = tabRequesters.getOrPut(tabId) { FocusRequester() }

    // ---- Spotlight state
    // Watchlist titles that just became watchable go first; then this week's titles,
    // minus dismissed ones and (optionally) anything not on an installed app.
    val spotList = remember(spotItems, config.watchlist, watchStatus, config.onlyMine, config.notInterested, allApps) {
        val promoted = config.watchlist.mapNotNull { w ->
            val s = watchStatus[w.key] ?: return@mapNotNull null
            if (w.backdrop != null && watchLabel(w, s, allApps).promote) w.toSpotlight(s, "From your watchlist") else null
        }
        val promotedKeys = promoted.mapTo(HashSet()) { it.key }
        promoted + spotItems.filter {
            it.key !in config.notInterested && it.key !in promotedKeys &&
                (!config.onlyMine || providerApps(it.providers, allApps).isNotEmpty())
        }
    }
    val spotOn = config.spotlight && config.tmdbKey != null && spotList.isNotEmpty()
    var spotIndex by remember { mutableIntStateOf(0) }
    val spotItem = spotList.getOrNull(spotIndex.mod(spotList.size.coerceAtLeast(1)))
    var watchMenuFor by remember { mutableStateOf<WatchItem?>(null) }
    val watchCols = config.columns.coerceAtLeast(4)
    var spotFocused by remember { mutableStateOf(false) }
    var spotDetails by remember { mutableStateOf<SpotlightItem?>(null) }
    var gridFocused by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    // Scrolling past the first row hands the whole screen to the grid.
    // The spotlight stays put; only the two-row app window below it slides.
    val spotCollapsed = false
    val spotHeight = SPOTLIGHT_HEIGHT
    var windowTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(tabId) { windowTop = 0 }
    LaunchedEffect(movingKey, tabApps) {
        val i = tabApps.indexOfFirst { it.key == movingKey }
        if (i >= 0) windowTop = windowTopFor(i / config.columns, windowTop)
    }

    LaunchedEffect(spotOn, spotFocused, visible, spotCollapsed, spotList.size, spotDetails) {
        if (!spotOn || spotFocused || !visible || spotCollapsed || spotDetails != null) return@LaunchedEffect
        while (true) {
            delay(SPOTLIGHT_ROTATE_MS)
            spotIndex = (spotIndex + 1).mod(spotList.size)
        }
    }
    // Warm the next title's images so the crossfade never waits on the network.
    LaunchedEffect(spotItem, spotList.size) {
        if (!spotOn || spotList.size < 2) return@LaunchedEffect
        val next = spotList[(spotIndex + 1).mod(spotList.size)]
        app.spotlight.backdrop(next)
        app.spotlight.logo(next)
    }

    BackHandler(enabled = movingKey != null) {
        focusKey = movingKey
        movingKey = null
    }

    // HOME pressed while already home: reset to the default view, like stock launchers.
    LaunchedEffect(homeSignal) {
        if (homeSignal > 0) {
            menuFor = null
            categoriesFor = null
            movingKey = null
            selectedTab = defaultTab
        }
        pendingFocus = true
    }
    val gridCount = if (tabId == TAB_WATCH) config.watchlist.size else tabApps.size
    LaunchedEffect(pendingFocus, gridCount > 0) {
        if (!pendingFocus || gridCount == 0) return@LaunchedEffect
        windowTop = 0
        withFrameNanos { }
        runCatching { firstTile.requestFocus() }
        pendingFocus = false
    }

    fun moveBy(delta: Int) {
        val key = movingKey ?: return
        // Recompute from the latest prefs, not the last composition: key repeats can arrive
        // faster than recomposition, and a stale list would repeat the previous step.
        val latest = prefs.config.value
        val all = orderApps(allApps, latest.order)
        val list = appsForTab(all.filterNot { it.key in latest.hidden }, latest, tabId)
        val from = list.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to != from) prefs.moveApp(key, list[to].key, all.map { it.key })
    }

    /** After a tile disappears (hidden/unfavorited), park focus on its neighbour. */
    fun neighbourOf(entry: AppEntry): String? {
        val i = tabApps.indexOfFirst { it.key == entry.key }
        return tabApps.getOrNull(i + 1)?.key ?: tabApps.getOrNull(i - 1)?.key
    }

    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(config.wallpaper)
        if (config.spotlight) SpotlightBackdrop(app.spotlight, spotItem, shown = spotOn && !spotCollapsed)

        // 5% / 2.5% safe-area padding: keeps everything inside TV overscan.
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            TopBar(config, weather, net, actions, homeVisible = visible) { showNetwork = true }
            if (spotOn && spotItem != null) {
                Box(Modifier.fillMaxWidth().height(spotHeight).clipToBounds(), contentAlignment = Alignment.BottomStart) {
                    SpotlightPanel(
                        repo = app.spotlight,
                        item = spotItem,
                        index = spotIndex.mod(spotList.size),
                        count = spotList.size,
                        installed = allApps,
                        onFocusChanged = { spotFocused = it },
                        onStep = { spotIndex = (spotIndex + it).mod(spotList.size) },
                        onOpen = { spotDetails = spotItem },
                        modifier = Modifier.padding(start = 0.dp).fillMaxWidth(0.62f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TabRow(tabs, tabId, tabRequesters) { selectedTab = it }

            if (tabId == TAB_WATCH) {
                RowWindow(
                    count = config.watchlist.size,
                    columns = watchCols,
                    topRow = windowTop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onFocusChanged { gridFocused = it.hasFocus }
                        .focusRestorer(firstTile),
                ) { index, tileWidth ->
                    val w = config.watchlist[index]
                    val label = watchLabel(w, watchStatus[w.key], allApps)
                    WatchTile(
                        item = w,
                        label = label,
                        repo = app.spotlight,
                        wantsFocus = focusKey == w.key,
                        onFocusTaken = { focusKey = null },
                        onFocused = {
                            focusedIndex = index
                            windowTop = windowTopFor(index / watchCols, windowTop)
                        },
                        // Always show details first (overview, next episode, where to watch);
                        // the "Open <app>" button is inside.
                        onClick = { spotDetails = w.asSpotlight(watchStatus[w.key]) },
                        onMenu = { watchMenuFor = w },
                        modifier = Modifier.width(tileWidth),
                        externalRequester = if (index == 0) firstTile else null,
                        upTarget = if (index < watchCols) selectedTabRequester else null,
                        rowEnd = index % watchCols == watchCols - 1 || index == config.watchlist.lastIndex,
                    )
                }
            } else if (tabApps.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Note(
                        if (allApps.isEmpty()) "" else
                            "Nothing here yet. Hold OK on any app (or press Menu) and choose “Categories…”.",
                        color = Zen.TextFaint,
                    )
                }
            } else {
                RowWindow(
                    count = tabApps.size,
                    columns = config.columns,
                    topRow = windowTop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onFocusChanged { gridFocused = it.hasFocus }
                        .focusRestorer(firstTile),
                ) { index, tileWidth ->
                    val entry = tabApps[index]
                    key(entry.key) {
                        AppTile(
                            entry = entry,
                            repo = app.apps,
                            index = index,
                            columns = config.columns,
                            showLabel = config.showLabels,
                            favoriteMark = tabId != TAB_FAVORITES && entry.key in config.favorites,
                            moving = movingKey == entry.key,
                            wantsFocus = focusKey == entry.key,
                            onFocusTaken = { focusKey = null },
                            onClick = { actions.launch(entry) },
                            onMenu = { menuFor = entry },
                            onMove = ::moveBy,
                            onDrop = {
                                focusKey = movingKey
                                movingKey = null
                            },
                            modifier = Modifier.width(tileWidth),
                            externalRequester = if (index == 0) firstTile else null,
                            // First row: UP always goes to the selected tab, never the top bar.
                            upTarget = if (index < config.columns) selectedTabRequester else null,
                            // Row end: RIGHT stays put instead of escaping to the top-bar buttons.
                            rowEnd = index % config.columns == config.columns - 1 || index == tabApps.lastIndex,
                            onFocused = {
                                focusedIndex = index
                                windowTop = windowTopFor(index / config.columns, windowTop)
                            },
                        )
                    }
                }
            }

            if (movingKey != null) {
                Txt("Arrows move the app  ·  OK or Back to drop", Modifier.padding(top = 6.dp), size = 13.sp, color = Zen.TextFaint)
            }
        }

        menuFor?.let { entry ->
            val close = { menuFor = null; focusKey = entry.key }
            AppOptionsDialog(
                entry = entry,
                isFavorite = entry.key in config.favorites,
                onDismiss = close,
                onOpen = { menuFor = null; actions.launch(entry) },
                onFavorite = {
                    val leaving = tabId == TAB_FAVORITES && entry.key in config.favorites
                    menuFor = null
                    focusKey = if (leaving) neighbourOf(entry) else entry.key
                    prefs.toggleFavorite(entry.key)
                },
                onMove = { menuFor = null; movingKey = entry.key },
                onCategories = { menuFor = null; categoriesFor = entry },
                onHide = {
                    menuFor = null
                    focusKey = neighbourOf(entry)
                    prefs.setHidden(entry.key, true)
                },
                onInfo = { close(); actions.appInfo(entry) },
                onUninstall = { close(); actions.uninstall(entry) },
            )
        }

        spotDetails?.let { item ->
            val watched = config.watchlist.firstOrNull { it.key == item.key }
            SpotlightDialog(
                item = item,
                installed = allApps,
                region = config.place?.countryCode,
                onOpenApp = { spotDetails = null; actions.launch(it) },
                onDismiss = { spotDetails = null },
                inWatchlist = watched != null,
                onToggleWatchlist = {
                    if (watched != null) prefs.removeFromWatchlist(item.key) else prefs.addToWatchlist(item.toWatchItem())
                },
                onNotInterested = if (item.reason == "From your watchlist") null else ({
                    spotDetails = null
                    prefs.notInterested(item.key)
                }),
                status = watched?.let { w ->
                    listOfNotNull(watchLabel(w, watchStatus[w.key], allApps).text, watchDetail(w, watchStatus[w.key]))
                        .distinct().joinToString("\n")
                },
            )
        }

        if (showNetwork) {
            NetworkDialog(
                net = net,
                vpnApps = remember(allApps) { app.network.vpnApps(allApps) },
                onOpenApp = { showNetwork = false; actions.launch(it) },
                onOpenSettings = { showNetwork = false; actions.openSettingsPage(it) },
                onDismiss = { showNetwork = false },
            )
        }

        watchMenuFor?.let { w ->
            val label = watchLabel(w, watchStatus[w.key], allApps)
            WatchOptionsDialog(
                item = w,
                label = label,
                detail = watchDetail(w, watchStatus[w.key]),
                onOpenApp = { watchMenuFor = null; label.app?.let(actions.launch) },
                onDetails = { watchMenuFor = null; spotDetails = w.asSpotlight(watchStatus[w.key]) },
                onRemove = {
                    watchMenuFor = null
                    val i = config.watchlist.indexOfFirst { it.key == w.key }
                    focusKey = (config.watchlist.getOrNull(i + 1) ?: config.watchlist.getOrNull(i - 1))?.key
                    prefs.removeFromWatchlist(w.key)
                },
                onDismiss = { watchMenuFor = null; focusKey = w.key },
            )
        }

        categoriesFor?.let { entry ->
            CategoryPickerDialog(
                entry = entry,
                config = config,
                onToggle = { id -> prefs.toggleInCategory(id, entry.key) },
                onOpenSettings = { categoriesFor = null; actions.openSettings() },
                onDismiss = { categoriesFor = null; focusKey = entry.key },
            )
        }
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun rememberMinuteClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake exactly once per minute, on the minute. No per-second ticking.
            delay(60_000L - System.currentTimeMillis() % 60_000L + 20)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun TopBar(config: Config, weather: WeatherNow?, net: NetState, actions: HomeActions, homeVisible: Boolean, onNetwork: () -> Unit) {
    val context = LocalContext.current
    val now = rememberMinuteClock()
    val is24h = when (config.clock) {
        ClockFormat.SYSTEM -> AndroidDateFormat.is24HourFormat(context)
        ClockFormat.H12 -> false
        ClockFormat.H24 -> true
    }
    val locale = Locale.getDefault()
    val time = remember(now, is24h) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", locale).format(Date(now)) }
    val amPm = remember(now, is24h) { if (is24h) "" else SimpleDateFormat("a", locale).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now))
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(time, size = 46.sp, weight = FontWeight.Light)
                if (amPm.isNotEmpty()) Txt(amPm, Modifier.padding(start = 6.dp, bottom = 9.dp), size = 16.sp, color = Zen.TextDim)
            }
            Txt(date, size = 16.sp, color = Zen.TextDim)
        }
        Spacer(Modifier.weight(1f))

        if (config.showWeather && weather != null && weather.place == config.place) {
            WeatherChip(weather, config.fahrenheit, config.animateWeather && homeVisible)
            Spacer(Modifier.width(32.dp))
        }

        NetworkButton(net, onNetwork)
        Spacer(Modifier.width(12.dp))
        ZenIconButton(onClick = actions.openSystemSettings) { SlidersIcon(it) }
        Spacer(Modifier.width(12.dp))
        ZenIconButton(onClick = actions.openSettings) { GearIcon(it) }
    }
}

@Composable
private fun WeatherChip(weather: WeatherNow, fahrenheit: Boolean, animate: Boolean) {
    val temp = if (fahrenheit) weather.tempC * 9 / 5 + 32 else weather.tempC
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(weather.code, weather.isDay, animate, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Txt("${temp.roundToInt()}°", size = 22.sp)
            Txt(
                listOf(weatherLabel(weather.code), weather.place.name).filter { it.isNotEmpty() }.joinToString(" · "),
                size = 13.sp, color = Zen.TextDim,
            )
        }
    }
}

// ---------------------------------------------------------------- tabs

@Composable
private fun TabRow(
    tabs: List<TabSpec>,
    selected: String,
    requesters: HashMap<String, FocusRequester>,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // Entering the row from the grid or top bar always lands on the *selected* tab;
            // otherwise passing through the row would switch tabs as a side effect.
            .focusProperties {
                onEnter = { requesters[selected]?.requestFocus() }
            }
            .focusGroup()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val requester = requesters.getOrPut(tab.id) { FocusRequester() }
            TabChip(tab.title, tab.id == selected, Modifier.focusRequester(requester)) { onSelect(tab.id) }
        }
    }
}

/** TV convention: tabs switch on focus, no click needed. */
@Composable
private fun TabChip(title: String, selected: Boolean, modifier: Modifier, onFocused: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier
                .clip(Zen.Pill)
                .background(if (focused) Zen.Focus else Color.Transparent)
                .focusable(interactionSource = source)
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Txt(
                title,
                size = 18.sp,
                color = when {
                    focused -> Zen.OnFocus
                    selected -> Zen.Text
                    else -> Zen.TextDim
                },
                weight = if (selected || focused) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(width = 18.dp, height = 3.dp)
                .clip(Zen.Pill)
                .background(if (selected && !focused) Zen.Accent else Color.Transparent)
        )
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun AppOptionsDialog(
    entry: AppEntry,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    onCategories: () -> Unit,
    onHide: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val first = remember { FocusRequester() }
    ZenDialog(entry.label, onDismiss, initialFocus = first) {
        ZenListItem("Open", onOpen, Modifier.focusRequester(first))
        ZenListItem(if (isFavorite) "Remove from favorites" else "Add to favorites", onFavorite)
        ZenListItem("Move", onMove, detail = "Reorder with the arrow keys")
        ZenListItem("Categories…", onCategories)
        ZenListItem("Hide from home", onHide, detail = "Unhide in Settings → Hidden apps")
        ZenListItem("App info", onInfo)
        if (!entry.system) ZenListItem("Uninstall", onUninstall)
    }
}

@Composable
private fun CategoryPickerDialog(
    entry: AppEntry,
    config: Config,
    onToggle: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    ZenDialog("Categories · ${entry.label}", onDismiss, initialFocus = first) {
        if (config.categories.isEmpty()) {
            Note("You have no categories yet.", Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            ZenListItem("Create categories in Settings", onOpenSettings, Modifier.focusRequester(first))
        } else {
            config.categories.forEachIndexed { i, cat ->
                ToggleRow(
                    cat.name,
                    checked = entry.key in cat.apps,
                    onToggle = { onToggle(cat.id) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
            Spacer(Modifier.height(8.dp))
            ZenListItem("Done", onDismiss)
        }
    }
}

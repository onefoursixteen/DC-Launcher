package me.cazan.zen.ui

import android.os.Debug
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import me.cazan.zen.ensureRegion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import me.cazan.zen.ZenApp
import me.cazan.zen.data.ClockFormat
import me.cazan.zen.data.Config
import me.cazan.zen.data.MAX_COLUMNS
import me.cazan.zen.data.MIN_COLUMNS
import me.cazan.zen.data.Wallpaper
import me.cazan.zen.data.WallpaperLoader
import kotlinx.coroutines.launch
import java.io.File

class SettingsActions(
    val requestDefaultHome: () -> Unit,
    val openSystemSettings: () -> Unit,
    val pickWallpaperFile: () -> Unit,
    val wallpaperDir: File?,
    val versionName: String,
)

private enum class Section(val title: String) {
    HOME("Home app"),
    APPEARANCE("Appearance"),
    SPOTLIGHT("Spotlight"),
    WEATHER("Clock & weather"),
    HIDDEN("Hidden apps"),
    CATEGORIES("Categories"),
    REMOTE("Remote buttons"),
    ABOUT("About"),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(app: ZenApp, isDefaultHome: Boolean, actions: SettingsActions) {
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    val config by app.prefs.config.collectAsState()
    val firstNav = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstNav.requestFocus() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Zen.Background)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Column(
            Modifier
                .width(250.dp)
                .fillMaxHeight()
                .focusRestorer()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Txt("Settings", Modifier.padding(start = 18.dp, bottom = 20.dp), size = 30.sp, weight = FontWeight.Light)
            Section.entries.forEachIndexed { i, s ->
                NavItem(
                    s.title,
                    selected = s == section,
                    modifier = if (i == 0) Modifier.focusRequester(firstNav) else Modifier,
                ) { section = s }
            }
        }
        Spacer(Modifier.width(40.dp))
        Box(Modifier.weight(1f).fillMaxHeight().focusGroup()) {
            when (section) {
                Section.HOME -> HomeAppSection(isDefaultHome, actions)
                Section.APPEARANCE -> AppearanceSection(app, config, actions)
                Section.SPOTLIGHT -> SpotlightSection(app, config)
                Section.WEATHER -> ClockWeatherSection(app, config)
                Section.HIDDEN -> HiddenAppsSection(app, config)
                Section.CATEGORIES -> CategoriesSection(app, config)
                Section.REMOTE -> RemoteSection(app, config)
                Section.ABOUT -> AboutSection(actions)
            }
        }
    }
}

/** Left-hand navigation: focusing an entry shows its page (no click), like system TV settings. */
@Composable
private fun NavItem(title: String, selected: Boolean, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }
    Box(
        modifier
            .fillMaxWidth()
            .clip(Zen.Row)
            .background(
                when {
                    focused -> Zen.Focus
                    selected -> Zen.Surface
                    else -> Color.Transparent
                }
            )
            .focusable(interactionSource = source)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Txt(
            title,
            size = 18.sp,
            color = when {
                focused -> Zen.OnFocus
                selected -> Zen.Text
                else -> Zen.TextDim
            },
        )
    }
}

@Composable
private fun ScrollPage(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun CodeLine(text: String) {
    BasicText(
        text,
        Modifier
            .clip(Zen.Row)
            .background(Color(0xFF05070A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = TextStyle(color = Zen.Accent, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
    )
}

// ---------------------------------------------------------------- Home app

@Composable
private fun HomeAppSection(isDefaultHome: Boolean, actions: SettingsActions) = ScrollPage {
    SectionTitle("Home app")
    Note(
        if (isDefaultHome) "✓  DC Launcher is your default home screen."
        else "DC Launcher is not the default home screen yet.",
        color = if (isDefaultHome) Zen.Accent else Zen.Text,
    )
    if (!isDefaultHome) ZenButton("Set as default home app", actions.requestDefaultHome)
    Spacer(Modifier.height(8.dp))
    Note("Many TVs (Google TV, PatchWall) hide the home-app picker. Run this once from a computer instead:")
    CodeLine("adb shell cmd package set-home-activity me.cazan.zen/.MainActivity")
    Spacer(Modifier.height(8.dp))
    ZenButton("Open Android system settings", actions.openSystemSettings)
}

// ---------------------------------------------------------------- Appearance

@Composable
private fun AppearanceSection(app: ZenApp, config: Config, actions: SettingsActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askUrl by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val localFiles = remember(actions.wallpaperDir) {
        actions.wallpaperDir?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }
    fun setWallpaper(w: Wallpaper) = app.prefs.update { it.copy(wallpaper = w) }

    ScrollPage {
        SectionTitle("Wallpaper")
        Label("Gradients")
        SwatchRow {
            GradientPresets.forEachIndexed { i, (name, _) ->
                Swatch(name, gradientBrush(i), selected = config.wallpaper == Wallpaper.Gradient(i)) {
                    setWallpaper(Wallpaper.Gradient(i))
                }
            }
        }
        Label("Solid colours")
        SwatchRow {
            SolidPresets.forEach { argb ->
                Swatch(null, SolidColor(Color(argb)), selected = config.wallpaper == Wallpaper.Solid(argb)) {
                    setWallpaper(Wallpaper.Solid(argb))
                }
            }
        }
        Label("Image")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZenButton("From a URL…", { askUrl = true })
            ZenButton("Browse files…", actions.pickWallpaperFile)
        }
        if (localFiles.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                localFiles.forEach { f ->
                    ZenButton(f.name, { setWallpaper(Wallpaper.Image(f.absolutePath)) },
                        selected = config.wallpaper == Wallpaper.Image(f.absolutePath))
                }
            }
        }
        actions.wallpaperDir?.let {
            Note("Tip: no file browser on your TV? Copy images here and they'll appear above:", color = Zen.TextFaint)
            CodeLine("adb push photo.jpg ${it.absolutePath}/")
        }
        (config.wallpaper as? Wallpaper.Image)?.let { Note("Current image: ${it.source}", color = Zen.TextFaint) }
        if (status.isNotEmpty()) Note(status, color = Zen.Accent)

        SectionTitle("Layout")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("Apps per row", Modifier.width(220.dp), size = 18.sp)
            ZenButton("−", { app.prefs.update { it.copy(columns = (it.columns - 1).coerceAtLeast(MIN_COLUMNS)) } },
                enabled = config.columns > MIN_COLUMNS)
            Txt("${config.columns}", Modifier.width(36.dp), size = 22.sp, weight = FontWeight.Medium)
            ZenButton("+", { app.prefs.update { it.copy(columns = (it.columns + 1).coerceAtMost(MAX_COLUMNS)) } },
                enabled = config.columns < MAX_COLUMNS)
        }
        ToggleRow("Always show app names", config.showLabels, { app.prefs.update { it.copy(showLabels = !it.showLabels) } },
            detail = "When off, a name only appears on the focused app")
    }

    if (askUrl) {
        TextInputDialog(
            title = "Wallpaper image URL",
            initial = (config.wallpaper as? Wallpaper.Image)?.source?.takeIf { it.startsWith("http") } ?: "https://",
            hint = "https://example.com/wallpaper.jpg",
            keyboardType = KeyboardType.Uri,
            onDismiss = { askUrl = false },
            onDone = { url ->
                askUrl = false
                status = "Downloading…"
                scope.launch {
                    val dm = context.resources.displayMetrics
                    val ok = url.startsWith("https://") &&
                        WallpaperLoader.load(context, url.trim(), dm.widthPixels, dm.heightPixels) != null
                    status = if (ok) {
                        setWallpaper(Wallpaper.Image(url.trim()))
                        "Wallpaper set."
                    } else {
                        "Couldn't load that image. Use an https:// link to a JPG, PNG or WebP file."
                    }
                }
            },
        )
    }
}

@Composable
private fun SwatchRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun Swatch(name: String?, brush: Brush, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 112.dp, height = 63.dp)
                .graphicsLayer { val s = if (focused) 1.1f else 1f; scaleX = s; scaleY = s }
                .clip(Zen.Card)
                .background(brush)
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) Zen.Focus else Color(0x33FFFFFF),
                    shape = Zen.Card,
                )
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).background(Zen.Accent, CircleShape))
        }
        if (name != null) Txt(name, Modifier.padding(top = 6.dp), size = 13.sp, color = if (focused) Zen.Text else Zen.TextDim)
    }
}

// ---------------------------------------------------------------- Spotlight

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(label, Modifier.weight(1f), size = 18.sp)
        Txt(value, size = 16.sp, color = Zen.Accent)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotlightSection(app: ZenApp, config: Config) {
    val admin by app.admin.status.collectAsState()
    val status by app.spotlight.status.collectAsState()
    val scope = rememberCoroutineScope()
    val pinBlock = remember { BringIntoViewRequester() }
    val place = config.place
    val region = place?.countryCode

    LaunchedEffect(place?.name) { app.ensureRegion() }
    LaunchedEffect(admin.running) { if (admin.running) runCatching { pinBlock.bringIntoView() } }

    ScrollPage {
        SectionTitle("Spotlight")
        Note("This week's new movies and series, with their artwork, at the top of the home screen. " +
            "Add titles to your watchlist from the spotlight, or search on the web admin page.")
        ToggleRow(
            "Show spotlight", config.spotlight, { app.prefs.update { it.copy(spotlight = !it.spotlight) } },
            detail = if (config.tmdbKey == null) "Needs a TMDB key: add it with the web admin page" else null,
        )
        ToggleRow(
            "Only show titles on my apps", config.onlyMine, { app.prefs.update { it.copy(onlyMine = !it.onlyMine) } },
            detail = "Hide titles that aren't streaming on an app installed on this TV",
        )
        ToggleRow(
            "Web admin page", admin.running, { if (admin.running) app.admin.stop() else app.admin.start() },
            detail = "Type the TMDB key on your phone or computer instead of with the remote",
        )
        if (admin.running) {
            Column(Modifier.bringIntoViewRequester(pinBlock).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("On a phone or computer on the same network, open:")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CodeLine(admin.url.orEmpty())
                    Txt("PIN", size = 18.sp, color = Zen.TextDim)
                    Txt(admin.pin.orEmpty().chunked(3).joinToString(" "), size = 34.sp, weight = FontWeight.Medium)
                }
                Note("Turns itself off when you leave Settings, after 15 minutes, or after 5 wrong PINs.", color = Zen.TextFaint)
            }
        }
        if (admin.event.isNotEmpty()) Note(admin.event, Modifier.padding(horizontal = 18.dp), color = Zen.Accent)

        InfoRow("TMDB key", config.tmdbKey?.let { "Saved  ••••${it.takeLast(4)}" } ?: "Not set")
        InfoRow("Watchlist", config.watchlist.size.let { if (it == 1) "1 title" else "$it titles" })
        if (config.notInterested.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ZenButton("Show hidden titles again", { app.prefs.update { it.copy(notInterested = emptySet()) } })
                Txt("${config.notInterested.size} marked “Not interested”", size = 15.sp, color = Zen.TextDim)
            }
        }
        InfoRow(
            "Region",
            when {
                region != null -> "${place.country ?: region} ($region)"
                place != null -> "Looking up ${place.name}…"
                else -> "Set a weather city first"
            },
        )
        Note("The region comes from your city in Clock & weather.", Modifier.padding(horizontal = 18.dp), color = Zen.TextFaint)
        val key = config.tmdbKey
        if (key != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ZenButton("Refresh now", { scope.launch { app.spotlight.refreshIfStale(key, region, force = true) } })
                if (status.isNotEmpty()) Txt(status, size = 15.sp, color = Zen.TextDim)
            }
        }
        Note("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Zen.TextFaint)
    }
}

// ---------------------------------------------------------------- Clock & weather

@Composable
private fun ClockWeatherSection(app: ZenApp, config: Config) {
    val scope = rememberCoroutineScope()
    var askCity by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    ScrollPage {
        SectionTitle("Clock")
        ValueRow("Clock format", config.clock.label, {
            app.prefs.update { it.copy(clock = ClockFormat.entries[(it.clock.ordinal + 1) % ClockFormat.entries.size]) }
        })
        SectionTitle("Weather")
        ToggleRow("Show weather on the home screen", config.showWeather,
            { app.prefs.update { it.copy(showWeather = !it.showWeather) } })
        ValueRow("City", config.place?.name ?: "Not set", { askCity = true })
        ToggleRow("Use Fahrenheit", config.fahrenheit, { app.prefs.update { it.copy(fahrenheit = !it.fahrenheit) } })
        ToggleRow(
            "Animate weather icon", config.animateWeather,
            { app.prefs.update { it.copy(animateWeather = !it.animateWeather) } },
            detail = "Falling rain and snow, drifting clouds. Off shows a still icon.",
        )
        if (status.isNotEmpty()) Note(status, color = Zen.Accent)
        Note(
            "Weather comes from Open-Meteo: free, no account, no API key. Only your city's coordinates " +
                "are sent, at most every 15 minutes and only while the home screen is visible.",
            color = Zen.TextFaint,
        )
    }

    if (askCity) {
        TextInputDialog(
            title = "Your city",
            initial = config.place?.name.orEmpty(),
            hint = "e.g. Lisbon",
            onDismiss = { askCity = false },
            onDone = { query ->
                askCity = false
                if (query.isBlank()) return@TextInputDialog
                status = "Looking up “${query.trim()}”…"
                scope.launch {
                    val place = app.weather.geocode(query)
                    status = if (place == null) {
                        "Couldn't find that city (or the TV is offline)."
                    } else {
                        app.prefs.update { it.copy(place = place, showWeather = true) }
                        "Weather location set to ${place.name}."
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- Hidden apps

@Composable
private fun HiddenAppsSection(app: ZenApp, config: Config) {
    val apps by app.apps.apps.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Hidden apps")
        Note("Switch an app off to hide it from the home screen. It stays installed and keeps working.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZenButton("Show all apps", { app.prefs.update { it.copy(hidden = emptySet()) } }, enabled = config.hidden.isNotEmpty())
            ZenButton("Reset app order", { app.prefs.resetOrder() }, enabled = config.order.isNotEmpty())
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(apps, key = { _, e -> e.key }) { _, e ->
                val visible = e.key !in config.hidden
                ToggleRow(
                    e.label,
                    checked = visible,
                    onToggle = { app.prefs.setHidden(e.key, visible) },
                    detail = listOfNotNull(
                        if (e.system) "System app" else null,
                        if (!e.leanback) "Phone app (sideloaded)" else null,
                    ).joinToString(" · ").ifEmpty { null },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Categories

@Composable
private fun CategoriesSection(app: ZenApp, config: Config) {
    val apps by app.apps.apps.collectAsState()
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.key } }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }

    ScrollPage {
        SectionTitle("Categories")
        Note("Each category becomes a tab on the home screen. You can also add an app to a category by holding OK on it.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZenButton("New category…", { creating = true })
            listOf("Streaming", "Games", "Utilities")
                .filter { s -> config.categories.none { it.name.equals(s, ignoreCase = true) } }
                .forEach { s -> ZenButton("+ $s", { app.prefs.addCategory(s) }) }
        }
        Spacer(Modifier.height(4.dp))
        if (config.categories.isEmpty()) Note("No categories yet.", color = Zen.TextFaint)
        config.categories.forEachIndexed { i, cat ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(cat.name, Modifier.width(200.dp), size = 18.sp, weight = FontWeight.Medium)
                Txt("${cat.apps.count { it in installed }} apps", Modifier.width(80.dp), size = 15.sp, color = Zen.TextDim)
                ZenButton("Apps…", { editing = cat.id })
                ZenButton("Rename", { renaming = cat.id })
                ZenButton("Move up", { app.prefs.moveCategoryUp(cat.id) }, enabled = i > 0)
                ZenButton("Delete", { app.prefs.deleteCategory(cat.id) })
            }
        }
    }

    if (creating) {
        TextInputDialog("New category", "", "e.g. Kids", onDismiss = { creating = false },
            onDone = { app.prefs.addCategory(it); creating = false })
    }
    renaming?.let { id ->
        val cat = config.categories.firstOrNull { it.id == id }
        if (cat == null) renaming = null
        else TextInputDialog("Rename category", cat.name, "Name", onDismiss = { renaming = null },
            onDone = { app.prefs.renameCategory(id, it); renaming = null })
    }
    editing?.let { id ->
        val cat = config.categories.firstOrNull { it.id == id }
        if (cat == null) {
            editing = null
        } else {
            val first = remember { FocusRequester() }
            ZenDialog("Apps in ${cat.name}", { editing = null }, width = 560.dp, initialFocus = first, scrollable = false) {
                LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(apps.filter { it.key !in config.hidden }, key = { _, e -> e.key }) { index, e ->
                        ToggleRow(
                            e.label,
                            checked = e.key in cat.apps,
                            onToggle = { app.prefs.toggleInCategory(id, e.key) },
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                ZenButton("Done", { editing = null })
            }
        }
    }
}

// ---------------------------------------------------------------- About

@Composable
private fun RemoteSection(app: ZenApp, config: Config) {
    val apps by app.apps.apps.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val current = apps.firstOrNull { it.packageName == config.assistApp }?.label
        ?: config.assistApp ?: "Nothing"

    ScrollPage {
        SectionTitle("Remote buttons")
        ValueRow("Assistant button opens", current, { picking = true })
        Note(
            "Works once DC Launcher handles the Assistant button. Google Assistant must be removed or disabled, " +
                "then run this once from a computer:",
        )
        CodeLine("adb shell cmd role add-role-holder android.app.role.ASSISTANT me.cazan.zen 0")
    }

    if (picking) {
        val first = remember { FocusRequester() }
        ZenDialog("Assistant button opens", { picking = false }, width = 560.dp, initialFocus = first, scrollable = false) {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "none") {
                    ToggleRow("Nothing", config.assistApp == null, {
                        app.prefs.update { it.copy(assistApp = null) }
                        picking = false
                    }, Modifier.focusRequester(first))
                }
                itemsIndexed(apps, key = { _, e -> e.key }) { _, e ->
                    ToggleRow(e.label, config.assistApp == e.packageName, {
                        app.prefs.update { it.copy(assistApp = e.packageName) }
                        picking = false
                    })
                }
            }
        }
    }
}

@Composable
private fun AboutSection(actions: SettingsActions) {
    val memInfo = remember { Debug.getPss() / 1024 } // KB → MB, read once when the page opens
    ScrollPage {
        SectionTitle("DC Launcher ${actions.versionName}")
        Note("No ads. No recommendations. No analytics, crash reporting or tracking of any kind.")
        Note("The network is only used for the weather and URL wallpapers you set up yourself.")
        Note("Memory in use right now: about $memInfo MB.", color = Zen.Accent)
        Spacer(Modifier.height(8.dp))
        Label("Remote shortcuts")
        Note("OK: open app  ·  Hold OK or Menu: app options  ·  Home: back to the top  ·  Menu on empty space: these settings")
    }
}

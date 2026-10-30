package me.cazan.zen

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import me.cazan.zen.data.AppEntry
import me.cazan.zen.data.WeatherPlace
import me.cazan.zen.ui.HomeActions
import me.cazan.zen.ui.HomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class TmdbWork(val spotlight: Boolean, val key: String?, val place: WeatherPlace?, val watchKeys: List<String>)

class MainActivity : ComponentActivity() {

    private val app get() = application as ZenApp

    /** Incremented when HOME is pressed while the launcher is already showing. */
    private var homeSignal by mutableIntStateOf(0)
    private var homeVisible by mutableStateOf(false)
    private var started = false
    private var lastRepeatAt = 0L

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let(app.apps::invalidate)
            lifecycleScope.launch { app.apps.refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A launcher must never "exit" on Back. Compose BackHandlers (dialogs, move mode)
        // are registered later, so they take priority over this root no-op.
        onBackPressedDispatcher.addCallback(this) { }

        setContent {
            HomeScreen(
                app = app,
                homeSignal = homeSignal,
                visible = homeVisible,
                actions = HomeActions(
                    launch = ::launch,
                    openSettings = ::openSettings,
                    openSystemSettings = { safeStart(Intent(Settings.ACTION_SETTINGS)) },
                    openSettingsPage = { action -> safeStart(Intent(action)) },
                    appInfo = { e ->
                        safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.packageName}")))
                    },
                    uninstall = { e -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.packageName}"))) },
                ),
            )
        }
        // Compose paints the full screen; drop the window background to save one full-screen
        // overdraw pass per frame (the theme background still serves as the launch splash).
        window.setBackgroundDrawable(null)

        ContextCompat.registerReceiver(
            this,
            packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        lifecycleScope.launch { app.apps.refresh() }

        // Weather only refreshes while the home screen is visible; nothing runs in the background.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.prefs.config
                    .map { if (it.showWeather) it.place else null }
                    .distinctUntilChanged()
                    .collectLatest { place ->
                        if (place == null) return@collectLatest
                        while (true) {
                            app.weather.refresh(place)
                            delay(15 * 60_000L)
                        }
                    }
            }
        }

        // Spotlight (every 12 h) and watchlist status (every 20 h), checked hourly and only while
        // home is visible. Adding a watchlist title restarts this, so it gets a badge right away.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.prefs.config
                    .map { c -> TmdbWork(c.spotlight, c.tmdbKey, c.place, c.watchlist.map { it.key }) }
                    .distinctUntilChanged()
                    .collectLatest { work ->
                        // Filling in the region updates prefs, which restarts this collector.
                        app.ensureRegion()
                        val key = work.key ?: return@collectLatest
                        val region = work.place?.countryCode
                        while (true) {
                            if (work.spotlight) app.spotlight.refreshIfStale(key, region)
                            app.watchlist.refresh(key, region, app.prefs.config.value.watchlist)
                            delay(60 * 60_000L)
                        }
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        homeVisible = true
        app.network.start()
    }

    override fun onStop() {
        started = false
        homeVisible = false
        app.network.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing HOME from another app just brings us back where we were;
        // pressing it while already home resets to the top of the default tab.
        if (started && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeSignal++
        }
    }

    /**
     * Throttle held-down D-pad auto-repeat. Remotes fire repeats every ~30-50 ms; on slow TV
     * chips each one costs a focus search + scroll + recomposition, so the grid falls behind
     * and "overshoots" when the key is released. Capping at ~16 moves/s keeps it responsive.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 && event.keyCode in DPAD_KEYS) {
            val now = SystemClock.uptimeMillis()
            if (now - lastRepeatAt < REPEAT_INTERVAL_MS) return true
            lastRepeatAt = now
        }
        return super.dispatchKeyEvent(event)
    }

    /** Keys nothing in the UI consumed. MENU on empty space / settings key → our settings. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun launch(entry: AppEntry) {
        try {
            startActivity(app.apps.launchIntent(entry))
        } catch (_: ActivityNotFoundException) {
            app.apps.fallbackLaunchIntent(entry)?.let(::safeStart) ?: toast("Can't open ${entry.label}")
        } catch (_: SecurityException) {
            toast("Can't open ${entry.label}")
        }
    }

    private fun openSettings() = safeStart(Intent(this, SettingsActivity::class.java))

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Not available on this TV")
        } catch (_: SecurityException) {
            toast("Not available on this TV")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REPEAT_INTERVAL_MS = 60L
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}

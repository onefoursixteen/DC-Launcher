package me.cazan.zen

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import me.cazan.zen.data.Wallpaper
import me.cazan.zen.ui.SettingsActions
import me.cazan.zen.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsActivity : ComponentActivity() {

    private val app get() = application as ZenApp
    private var isDefaultHome by mutableStateOf(false)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultHome = checkDefaultHome()
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importWallpaper(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()

        setContent {
            SettingsScreen(
                app = app,
                isDefaultHome = isDefaultHome,
                actions = SettingsActions(
                    requestDefaultHome = ::requestDefaultHome,
                    openSystemSettings = { safeStart(Intent(Settings.ACTION_SETTINGS)) },
                    pickWallpaperFile = ::pickWallpaper,
                    wallpaperDir = getExternalFilesDir("wallpapers"),
                    versionName = version,
                ),
            )
        }
        window.setBackgroundDrawable(null)
        if (app.apps.apps.value.isEmpty()) lifecycleScope.launch { app.apps.refresh() }
    }

    override fun onResume() {
        super.onResume()
        isDefaultHome = checkDefaultHome()
    }

    override fun onStop() {
        // The admin page only lives while Settings is on screen.
        app.admin.stop()
        super.onStop()
    }

    private fun checkDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }

    /** RoleManager (Android 10+) → system "Home app" screen → tell the user about adb. */
    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        val candidates = buildList {
            add(Intent(Settings.ACTION_HOME_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null && safeStart(intent)) return
        }
        toast("This TV has no home-app picker. Use the adb command shown below.")
    }

    private fun pickWallpaper() {
        try {
            pickImage.launch(arrayOf("image/*"))
        } catch (_: ActivityNotFoundException) {
            toast("No file browser on this TV. Use a URL, or copy images with adb push.")
        }
    }

    /** Copies the picked image into private storage so we never depend on URI grants later. */
    private fun importWallpaper(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(filesDir, "wallpapers").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val dest = File(dir, "picked_${System.currentTimeMillis()}")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    dest
                }.getOrNull()
            }
            if (file == null) toast("Couldn't read that image.")
            else app.prefs.update { it.copy(wallpaper = Wallpaper.Image(file.absolutePath)) }
        }
    }

    private fun safeStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        toast("Not available on this TV")
        false
    } catch (_: SecurityException) {
        toast("Not available on this TV")
        false
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

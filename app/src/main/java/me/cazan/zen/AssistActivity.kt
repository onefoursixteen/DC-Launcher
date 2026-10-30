package me.cazan.zen

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Receives the remote's Assistant/voice button (ACTION_ASSIST and friends) and immediately
 * opens the app chosen in Settings → Remote buttons. No UI of its own; a plain Activity so
 * nothing from Compose is loaded on this path.
 */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = zen.prefs.config.value.assistApp
        val intent = pkg?.let {
            packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it)
        }
        if (intent != null) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Can't open the Assistant-button app", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(this, "Can't open the Assistant-button app", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Choose an app in DC Launcher settings → Remote buttons", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}

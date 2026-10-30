package me.cazan.zen.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.cazan.zen.data.AppEntry
import me.cazan.zen.data.NetState
import me.cazan.zen.data.NetType

/** Top-bar network indicator; a button so the network/VPN panel is one press away. */
@Composable
fun NetworkButton(net: NetState, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ZenIconButton(onClick = onClick) { tint ->
            when {
                net.type == NetType.OFFLINE -> OfflineIcon(tint)
                net.vpn -> VpnIcon(tint)
                net.type == NetType.ETHERNET -> EthernetIcon(tint)
                else -> WifiIcon(tint)
            }
        }
        if (net.type == NetType.OFFLINE) Txt("Offline", Modifier.padding(start = 8.dp), size = 14.sp, color = Zen.TextDim)
    }
}

@Composable
private fun InfoLine(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color = Zen.Text) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
        Txt(label, Modifier.weight(1f), size = 17.sp, color = Zen.TextDim)
        Txt(value, size = 17.sp, color = valueColor)
    }
}

/**
 * Connection details plus VPN controls. Android only lets the app that owns a VPN switch it
 * off, so "Disconnect" opens that app on its connection screen.
 */
@Composable
fun NetworkDialog(
    net: NetState,
    vpnApps: List<AppEntry>,
    onOpenApp: (AppEntry) -> Unit,
    onOpenSettings: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val hasVpnSettings = remember {
        Intent(Settings.ACTION_VPN_SETTINGS).resolveActivity(context.packageManager) != null
    }
    var firstUsed = false
    fun Modifier.firstFocus(): Modifier = if (!firstUsed) { firstUsed = true; focusRequester(first) } else this

    ZenDialog("Network", onDismiss, width = 560.dp, initialFocus = first) {
        InfoLine(
            "Connection",
            when (net.type) {
                NetType.ETHERNET -> "Ethernet"
                NetType.WIFI -> "Wi-Fi"
                NetType.OTHER -> "Connected"
                NetType.OFFLINE -> "Offline"
            },
        )
        net.ip?.let { InfoLine("IP address", it) }
        InfoLine("VPN", if (net.vpn) "Connected" else "Off", if (net.vpn) Zen.Accent else Zen.Text)
        Spacer(Modifier.height(10.dp))

        if (net.vpn) {
            vpnApps.forEach { app ->
                ZenListItem(
                    "Disconnect in ${app.label}", { onOpenApp(app) }, Modifier.firstFocus(),
                    detail = "Opens ${app.label}; press Disconnect there",
                )
            }
        } else {
            vpnApps.forEach { app -> ZenListItem("Connect with ${app.label}", { onOpenApp(app) }, Modifier.firstFocus()) }
        }
        if (hasVpnSettings) ZenListItem("VPN settings", { onOpenSettings(Settings.ACTION_VPN_SETTINGS) }, Modifier.firstFocus())
        ZenListItem(
            "Network settings",
            { onOpenSettings(if (net.type == NetType.WIFI) Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_WIRELESS_SETTINGS) },
            Modifier.firstFocus(),
        )
        ZenListItem("Close", onDismiss)
        if (net.vpn) {
            Note(
                "Android only lets the VPN app itself switch the VPN off, so DC Launcher opens it for you.",
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp), color = Zen.TextFaint,
            )
        }
    }
}

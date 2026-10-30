package me.cazan.zen.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

enum class NetType { ETHERNET, WIFI, OTHER, OFFLINE }

/** [type] is the underlying link; [vpn] is true when traffic goes through a VPN on top of it. */
data class NetState(val type: NetType, val vpn: Boolean, val ip: String?)

/** Callback-driven (no polling); registered only while the home screen is visible. */
class NetworkMonitor(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(NetState(NetType.OFFLINE, vpn = false, ip = null))
    private var callback: ConnectivityManager.NetworkCallback? = null

    val status: StateFlow<NetState> = state.asStateFlow()

    fun start() {
        state.value = current()
        if (callback != null || cm == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state.value = current() }
            override fun onLost(network: Network) { state.value = current() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { state.value = current() }
        }
        runCatching {
            // The default-network callback follows VPNs coming and going; a plain request
            // excludes VPN networks unless NOT_VPN is removed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) cm.registerDefaultNetworkCallback(cb)
            else cm.registerNetworkCallback(
                NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), cb,
            )
        }.onSuccess { callback = cb }
    }

    fun stop() {
        callback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        callback = null
    }

    fun refresh() { state.value = current() }

    private fun current(): NetState {
        val network = cm?.activeNetwork ?: return NetState(NetType.OFFLINE, false, null)
        val caps = cm.getNetworkCapabilities(network) ?: return NetState(NetType.OFFLINE, false, null)
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || anyVpnNetwork()
        // Since Android 12 a VPN's capabilities also carry its underlying transports.
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetType.WIFI
            else -> underlyingType()
        }
        return NetState(type, vpn, lanAddress(network, vpn))
    }

    /** The TV's home-network IPv4 (not the VPN tunnel's), e.g. for the web admin address. */
    @Suppress("DEPRECATION")
    private fun lanAddress(active: Network, vpn: Boolean): String? = runCatching {
        val candidates = if (!vpn) listOf(active) else cm!!.allNetworks.filter {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
        }
        candidates.firstNotNullOfOrNull { n ->
            cm!!.getLinkProperties(n)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }.getOrNull()

    @Suppress("DEPRECATION") // allNetworks is the only way to see a VPN's sibling networks on old APIs
    private fun anyVpnNetwork(): Boolean = runCatching {
        cm!!.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun underlyingType(): NetType = runCatching {
        val all = cm!!.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        when {
            all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> NetType.ETHERNET
            all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> NetType.WIFI
            else -> NetType.OTHER
        }
    }.getOrDefault(NetType.OTHER)

    /**
     * Installed apps that provide a VPN. Android doesn't tell other apps which one owns the
     * active tunnel, so the UI offers all of them (usually there's just one).
     */
    fun vpnApps(installed: List<AppEntry>): List<AppEntry> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
            .mapNotNull { it.serviceInfo }
            .filter { it.permission == android.Manifest.permission.BIND_VPN_SERVICE }
            .map { it.packageName }
            .toSet()
        return installed.filter { it.packageName in packages }
    }
}

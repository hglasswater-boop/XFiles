package app.local1st.files.core.fs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Integrates with the official WireGuard Android app; XFiles never handles VPN private keys. */
internal class SmbWireGuardAndroid(private val context: Context) {
    fun startTunnel(tunnel: String) = sendTunnelAction(tunnel, ACTION_TUNNEL_UP)

    fun stopTunnel(tunnel: String) = sendTunnelAction(tunnel, ACTION_TUNNEL_DOWN)

    private fun sendTunnelAction(tunnel: String, action: String) {
        val pm = context.packageManager
        val installed = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            throw IOException("公式 WireGuard アプリがインストールされていません。")
        }
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(
                "WireGuard操作権限がありません。XFilesのSMB接続設定から権限を許可してください。",
            )
        }
        try {
            // The official app exports this receiver with CONTROL_TUNNELS permission.
            // Explicit component delivery avoids an implicit broadcast being dropped.
            context.sendBroadcast(
                Intent(action).setClassName(PACKAGE, RECEIVER_CLASS).putExtra("tunnel", tunnel),
            )
            Log.i(LOG_TAG, "Sent WireGuard control request: action=$action, tunnel=$tunnel")
        } catch (error: SecurityException) {
            Log.w(LOG_TAG, "WireGuard control permission denied", error)
            throw SecurityException("WireGuardの外部操作が許可されていません。", error)
        }
    }

    companion object {
        const val PACKAGE = "com.wireguard.android"
        const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"
        private const val ACTION_TUNNEL_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
        private const val ACTION_TUNNEL_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
        const val RECEIVER_CLASS = "com.wireguard.android.model.TunnelManager\$IntentReceiver"
        const val LOG_TAG = "XFilesWireGuard"
    }
}


/**
 * Verify a NAS by comparing its address to the physical Wi-Fi interface's real IP prefix,
 * THEN checking the SMB TCP port using a socket bound to that same Wi-Fi Network.
 * Do not trust matching ranges alone: another network can reuse common private IPs.
 *
 * Executed when a configured SMB connection is opened, not on Wi-Fi state changes.
 */
internal fun isSmbReachableOnPhysicalWifi(context: Context, host: String, port: Int): Boolean =
    runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false

        manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return@any false
            }

            val wifiPrefixes = manager.getLinkProperties(network)?.linkAddresses
                ?.map { it.address to it.prefixLength }
                .orEmpty()
            if (wifiPrefixes.isEmpty()) {
                Log.d(SmbWireGuardAndroid.LOG_TAG, "Physical Wi-Fi has no readable IP prefix")
                return@any false
            }
            val candidates = runCatching {
                matchingWifiSubnetAddresses(network.getAllByName(host).toList(), wifiPrefixes)
            }.onFailure {
                Log.w(SmbWireGuardAndroid.LOG_TAG, "Wi-Fi DNS lookup failed", it)
            }.getOrDefault(emptyList())

            if (candidates.isEmpty()) {
                Log.d(SmbWireGuardAndroid.LOG_TAG, "NAS IP not in the connected Wi-Fi subnet")
                return@any false
            }
            candidates.any { target ->
                runCatching {
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(target, port), 600)
                        true
                    }
                }.onSuccess {
                    Log.i(SmbWireGuardAndroid.LOG_TAG, "Same-subnet NAS port reachable on physical Wi-Fi")
                }.onFailure {
                    Log.d(SmbWireGuardAndroid.LOG_TAG, "Same-subnet NAS not TCP-reachable on physical Wi-Fi", it)
                }.getOrDefault(false)
            }
        }
    }.onFailure {
        Log.w(SmbWireGuardAndroid.LOG_TAG, "Physical Wi-Fi route check failed", it)
    }.getOrDefault(false)

/** Single TCP probe used to distinguish a dead host from SMB negotiation failures. */
internal fun isSmbPortReachable(host: String, port: Int): Boolean = try {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 700)
        true
    }
} catch (_: IOException) {
    false
}

/** Reachability (not broadcast delivery) is the only signal to retry an SMB connection. */
internal fun awaitSmbPort(host: String, port: Int): Boolean {
    val deadline = System.nanoTime() + 8_000_000_000L
    do {
        if (isSmbPortReachable(host, port)) return true
        if (Thread.currentThread().isInterrupted) return false
        val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
        if (remainingMs <= 0L) return false
        try {
            Thread.sleep(minOf(400L, remainingMs))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    } while (System.nanoTime() < deadline)
    return false
}

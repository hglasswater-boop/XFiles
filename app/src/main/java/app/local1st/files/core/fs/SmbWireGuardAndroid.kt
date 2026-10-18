package app.local1st.files.core.fs

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.InetAddress
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
            context.sendBroadcast(
                Intent(action).setPackage(PACKAGE).putExtra("tunnel", tunnel),
            )
        } catch (error: SecurityException) {
            throw SecurityException("WireGuardの外部操作が許可されていません。", error)
        }
    }

    companion object {
        const val PACKAGE = "com.wireguard.android"
        const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"
        private const val ACTION_TUNNEL_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
        private const val ACTION_TUNNEL_DOWN = "com.wireguard.android.action.SET_TUNNEL_DOWN"
    }
}


/**
 * Checks NAS reachability on a physically bound Wi-Fi socket, never via an active VPN.
 * Only a confirmed VPN + physical Wi-Fi + local subnet + reachable SMB port authorizes DOWN.
 * Do not stop a tunnel when any part of this check is unknown.
 */
internal fun isSmbReachableOnPhysicalWifi(context: Context, host: String, port: Int): Boolean =
    runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val defaultNetwork = manager.activeNetwork ?: return@runCatching false
        val defaultCaps = manager.getNetworkCapabilities(defaultNetwork)
            ?: return@runCatching false
        if (!defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@runCatching false

        manager.allNetworks.any { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return@any false
            }
            val addresses = manager.getLinkProperties(network)?.linkAddresses ?: return@any false
            val targets = runCatching { network.getAllByName(host) }.getOrDefault(emptyArray())
            targets.any { target ->
                // A remotely routed NAS is not proof that the phone is on the home LAN.
                if (addresses.none { isAddressInSubnet(target, it.address, it.prefixLength) }) {
                    return@any false
                }
                runCatching {
                    network.socketFactory.createSocket().use { socket ->
                        socket.connect(InetSocketAddress(target, port), 600)
                        true
                    }
                }.getOrDefault(false)
            }
        }
    }.getOrDefault(false)

/** Pure comparison of a destination to a Wi-Fi link's IP prefix. */
internal fun isAddressInSubnet(target: InetAddress, local: InetAddress, prefixLength: Int): Boolean {
    val t = target.address
    val l = local.address
    if (t.size != l.size || prefixLength <= 0 || prefixLength > t.size * 8) return false
    for (index in 0 until prefixLength / 8) {
        if (t[index] != l[index]) return false
    }
    val bits = prefixLength % 8
    if (bits == 0) return true
    val mask = 0xff shl (8 - bits)
    return (t[prefixLength / 8].toInt() and mask) ==
        (l[prefixLength / 8].toInt() and mask)
}

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

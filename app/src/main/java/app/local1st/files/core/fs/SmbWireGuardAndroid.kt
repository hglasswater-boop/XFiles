package app.local1st.files.core.fs

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Integrates with the official WireGuard Android app; XFiles never handles VPN private keys. */
internal class SmbWireGuardAndroid(private val context: Context) {
    fun startTunnel(tunnel: String) {
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
                Intent(ACTION_TUNNEL_UP).setPackage(PACKAGE).putExtra("tunnel", tunnel),
            )
        } catch (error: SecurityException) {
            throw SecurityException("WireGuardの外部操作が許可されていません。", error)
        }
    }

    companion object {
        const val PACKAGE = "com.wireguard.android"
        const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"
        private const val ACTION_TUNNEL_UP = "com.wireguard.android.action.SET_TUNNEL_UP"
    }
}

/** Reachability (not the broadcast delivery) is the only signal to attempt an SMB retry. */
internal fun awaitSmbPort(host: String, port: Int): Boolean {
    val deadline = System.nanoTime() + 8_000_000_000L
    do {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 700)
                return true
            }
        } catch (_: IOException) {
            // VPN routing and DNS might still be coming up.
        }
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

package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException

/**
 * Route selection happens before SMBJ: avoid waiting for an OS-level TCP timeout
 * before sending a WireGuard UP request. Authentication/file I/O are never retried.
 */
class SmbWireGuardRetry(
    private val startTunnel: (String) -> Unit,
    private val awaitReachable: (String, Int) -> Boolean,
    private val isReachable: (String, Int) -> Boolean = { _, _ -> false },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val stopTunnel: (String) -> Unit = {},
    private val localReachable: (String, Int) -> Boolean = { _, _ -> false },
) {
    private val requestLock = Any()
    private val previousStarts = mutableMapOf<String, Long>()
    private val previousStops = mutableMapOf<String, Long>()

    fun <T> connect(config: SmbConnectionConfig, openTcp: () -> T): T {
        val tunnel = config.wireGuardTunnel.takeIf(String::isNotBlank)
            ?: return openTcp() // Existing opt-out SMB connections incur no extra probes.

        // Physical Wi-Fi reachability does not depend on Android's default VPN route.
        if (runCatching { localReachable(config.host, config.port) }.getOrDefault(false)) {
            synchronized(requestLock) {
                val now = nowMillis()
                val previous = previousStops[tunnel]
                if (previous == null || now < previous || now - previous >= REQUEST_COOLDOWN_MS) {
                    // DOWN is idempotent. A denied request must not block direct LAN access.
                    runCatching { stopTunnel(tunnel) }
                    previousStops[tunnel] = now
                }
            }
            // Do not rebound into an immediate UP request if the SMB connect fails.
            return openTcp()
        }

        // Fast probe: if port 445 is already reachable (LAN or existing VPN),
        // SMBJ owns any subsequent protocol/authentication error.
        if (isReachable(config.host, config.port)) return openTcp()

        synchronized(requestLock) {
            val now = nowMillis()
            val previous = previousStarts[tunnel]
            if (previous == null || now < previous || now - previous >= REQUEST_COOLDOWN_MS) {
                startTunnel(tunnel)
                previousStarts[tunnel] = now
            }
        }

        if (!awaitReachable(config.host, config.port)) {
            throw IOException(
                "WireGuard '" + tunnel + "' 起動後も " + config.host + ":" + config.port
                    + " に接続できません。トンネル名、リモート操作許可、AllowedIPsを確認してください。",
            )
        }
        // One SMBJ connect only; do not repeat authentication or file operations.
        return openTcp()
    }

    private companion object {
        const val REQUEST_COOLDOWN_MS = 10_000L
    }
}

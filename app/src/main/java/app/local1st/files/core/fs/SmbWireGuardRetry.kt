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
    private val hasMatchingWifiSubnet: (String) -> Boolean = { false },
    private val hasActiveVpn: () -> Boolean = { false },
    private val awaitWifiDirect: (String, Int) -> Boolean = { _, _ -> false },
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

        // Official WireGuard's userspace VPN does not enable Android VPN bypass.
        // A correctly bound physical Wi-Fi TCP probe can therefore fail while the VPN
        // is active. Only for a matching real Wi-Fi subnet may we briefly request DOWN,
        // verify Wi-Fi reachability before SMB authentication, and restore if necessary.
        val mayTryLanCutover = runCatching {
            hasMatchingWifiSubnet(config.host) && hasActiveVpn()
        }.getOrDefault(false)
        if (mayTryLanCutover) {
            var downRequested = false
            synchronized(requestLock) {
                val now = nowMillis()
                val previous = previousStops[tunnel]
                if (previous == null || now < previous || now - previous >= REQUEST_COOLDOWN_MS) {
                    // The receiver has no ACK; broadcast delivery is only a request.
                    if (runCatching { stopTunnel(tunnel) }.isSuccess) {
                        previousStops[tunnel] = now
                        downRequested = true
                    }
                }
            }
            if (downRequested) {
                if (awaitWifiDirect(config.host, config.port)) {
                    return openTcp()
                }
                // Do not accidentally start WireGuard on top of another active VPN.
                // Restore only after observing that VPN connectivity disappeared.
                if (!runCatching { hasActiveVpn() }.getOrDefault(true)) {
                    startTunnel(tunnel)
                    synchronized(requestLock) { previousStarts[tunnel] = nowMillis() }
                    if (!awaitReachable(config.host, config.port)) {
                        throw IOException(
                            "Wi-Fi直接接続を確認できずWireGuard '" + tunnel +
                                "' の復旧も確認できません。VPNの接続状態をご確認ください。",
                        )
                    }
                    return openTcp()
                }
            }
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

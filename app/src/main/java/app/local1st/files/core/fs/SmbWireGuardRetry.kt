package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException

/**
 * Retries the TCP connection only, never the SMB authentication or an in-progress file operation.
 * In particular, callers must wrap only SMBClient.connect(...) in [connect].
 */
internal class SmbWireGuardRetry(
    private val startTunnel: (String) -> Unit,
    private val awaitReachable: (String, Int) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val requestLock = Any()
    private val previousStarts = mutableMapOf<String, Long>()

    fun <T> connect(config: SmbConnectionConfig, openTcp: () -> T): T {
        try {
            return openTcp()
        } catch (tcpFailure: Exception) {
            val tunnel = config.wireGuardTunnel.takeIf(String::isNotBlank) ?: throw tcpFailure
            synchronized(requestLock) {
                val now = nowMillis()
                val previous = previousStarts[tunnel]
                if (previous == null || now < previous || now - previous >= REQUEST_COOLDOWN_MS) {
                    // A failed request should remain retryable after the user fixes permissions.
                    startTunnel(tunnel)
                    previousStarts[tunnel] = now
                }
                if (!awaitReachable(config.host, config.port)) {
                    throw IOException(
                        "WireGuard '" + tunnel + "' 起動後も " + config.host + ":" + config.port
                            + " に接続できません。WireGuardのトンネル名、リモート操作許可、AllowedIPsを確認してください。",
                        tcpFailure,
                    )
                }
            }
            // Do not catch this attempt: a second failure must not cause an infinite loop.
            return openTcp()
        }
    }

    private companion object {
        const val REQUEST_COOLDOWN_MS = 10_000L
    }
}

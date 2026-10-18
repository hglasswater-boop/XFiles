package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbWireGuardRetryTest {
    private fun connection(tunnel: String = "home") = SmbConnectionConfig(
        id = "nas-1",
        name = "NAS",
        host = "192.168.1.2",
        share = "files",
        wireGuardTunnel = tunnel,
    )

    @Test
    fun `direct connection never activates VPN`() {
        var starts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            awaitReachable = { _, _ -> error("no fallback expected") },
        )

        assertEquals("connected", retry.connect(connection()) { "connected" })
        assertEquals(0, starts)
    }

    @Test
    fun `reachable port with protocol failure never activates VPN`() {
        var starts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            isReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("VPN must not be started") },
        )
        val negotiationFailure = IOException("SMB protocol mismatch")
        val thrown = assertThrows(IOException::class.java) {
            retry.connect(connection()) { throw negotiationFailure }
        }
        assertSame(negotiationFailure, thrown)
        assertEquals(0, starts)
    }

    @Test
    fun `unconfigured connection never activates VPN`() {
        var starts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            awaitReachable = { _, _ -> error("no fallback expected") },
        )
        val firstError = IOException("connect refused")

        val thrown = assertThrows(IOException::class.java) {
            retry.connect(connection(tunnel = "")) { throw firstError }
        }
        assertSame(firstError, thrown)
        assertEquals(0, starts)
    }

    @Test
    fun `unreachable host starts tunnel and retries connection once`() {
        val started = mutableListOf<String>()
        var attempts = 0
        var checkedHost = ""
        val retry = SmbWireGuardRetry(
            startTunnel = { started.add(it) },
            awaitReachable = { host, port ->
                checkedHost = "$host:$port"
                true
            },
        )

        val result = retry.connect(connection()) {
            attempts++
            if (attempts == 1) throw IOException("no route")
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(2, attempts)
        assertEquals(listOf("home"), started)
        assertEquals("192.168.1.2:445", checkedHost)
    }

    @Test
    fun `VPN timeout fails without opening SMB connection again`() {
        var attempts = 0
        var started = false
        val retry = SmbWireGuardRetry(
            startTunnel = { started = true },
            awaitReachable = { _, _ -> false },
        )
        val thrown = assertThrows(IOException::class.java) {
            retry.connect(connection()) {
                attempts++
                throw IOException("network unreachable")
            }
        }

        assertTrue(started)
        assertEquals(1, attempts)
        assertTrue(thrown.message!!.contains("WireGuard"))
    }

    @Test
    fun `authentication failure on retry is not retried again`() {
        var starts = 0
        var attempts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            awaitReachable = { _, _ -> true },
        )
        val authFailure = IOException("authentication failed")

        val thrown = assertThrows(IOException::class.java) {
            retry.connect(connection()) {
                attempts++
                if (attempts == 1) throw IOException("connect failed")
                throw authFailure
            }
        }
        assertSame(authFailure, thrown)
        assertEquals(2, attempts)
        assertEquals(1, starts)
    }

    @Test
    fun `concurrent-style failures for same tunnel are deduplicated`() {
        var starts = 0
        var clockMs = 100L
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            awaitReachable = { _, _ -> true },
            nowMillis = { clockMs },
        )
        repeat(2) {
            var attempts = 0
            retry.connect(connection()) {
                attempts++
                if (attempts == 1) throw IOException("no route")
                "ok"
            }
            assertEquals(2, attempts)
        }
        assertEquals(1, starts)
        clockMs += 11_000L
        var attempts = 0
        retry.connect(connection()) {
            attempts++
            if (attempts == 1) throw IOException("no route")
            "ok"
        }
        assertEquals(2, starts)
    }

    @Test
    fun `missing permission reports the error instead of retrying`() {
        var attempts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { throw SecurityException("WireGuard permission missing") },
            awaitReachable = { _, _ -> error("no polling expected") },
        )

        val thrown = assertThrows(SecurityException::class.java) {
            retry.connect(connection()) {
                attempts++
                throw IOException("network unreachable")
            }
        }
        assertTrue(thrown.message!!.contains("permission"))
        assertEquals(1, attempts)
    }
}

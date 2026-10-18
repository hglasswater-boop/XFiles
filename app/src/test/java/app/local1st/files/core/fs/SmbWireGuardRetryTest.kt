package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException
import java.net.InetAddress
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
    fun `NAS IPv4 matches Wi-Fi subnet only for the correct prefix`() {
        val nas = InetAddress.getByName("192.168.1.10")
        assertTrue(isAddressInSubnet(nas, InetAddress.getByName("192.168.1.25"), 24))
        org.junit.Assert.assertFalse(isAddressInSubnet(nas, InetAddress.getByName("192.168.10.25"), 24))
        org.junit.Assert.assertFalse(isAddressInSubnet(nas, InetAddress.getByName("10.0.0.10"), 8))
    }

    @Test
    fun `IPv4 target never matches an IPv6 network`() {
        org.junit.Assert.assertFalse(isAddressInSubnet(
            InetAddress.getByName("192.168.1.10"),
            InetAddress.getByName("2001:db8::1"),
            64,
        ))
    }

    @Test
    fun `verified local Wi-Fi stops configured tunnel before SMB connection`() {
        val operations = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { operations.add("up:$it") },
            stopTunnel = { operations.add("down:$it") },
            localReachable = { host, port ->
                assertEquals("192.168.1.2", host)
                assertEquals(445, port)
                true
            },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("connected", retry.connect(connection()) {
            operations.add("smb")
            "connected"
        })
        assertEquals(listOf("down:home", "smb"), operations)
    }

    @Test
    fun `local tunnel shutdown never rebounds with an immediate UP request`() {
        val operations = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { operations.add("up") },
            stopTunnel = { operations.add("down") },
            localReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no VPN start") },
        )
        val failedConnection = IOException("VPN network routing is changing")
        val thrown = assertThrows(IOException::class.java) {
            retry.connect(connection()) { throw failedConnection }
        }
        assertSame(failedConnection, thrown)
        assertEquals(listOf("down"), operations)
    }

    @Test
    fun `off is never requested without VPN-independent Wi-Fi reachability`() {
        var stops = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { },
            stopTunnel = { stops++ },
            localReachable = { _, _ -> false },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("connected", retry.connect(connection()) { "connected" })
        assertEquals(0, stops)
    }

    @Test
    fun `opt-out SMB connection never sends a down request`() {
        var probes = 0
        var stops = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { },
            stopTunnel = { stops++ },
            localReachable = { _, _ -> probes++; true },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("ok", retry.connect(connection(tunnel = "")) { "ok" })
        assertEquals(0, probes)
        assertEquals(0, stops)
    }

    @Test
    fun `failure to stop VPN does not block direct SMB access`() {
        val retry = SmbWireGuardRetry(
            startTunnel = { },
            stopTunnel = { throw SecurityException("remote control not enabled") },
            localReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
    }

    @Test
    fun `local down requests are deduplicated for repeated connections`() {
        var stops = 0
        var now = 100L
        val retry = SmbWireGuardRetry(
            startTunnel = { },
            stopTunnel = { stops++ },
            localReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no retry") },
            nowMillis = { now },
        )
        repeat(2) { assertEquals("ok", retry.connect(connection()) { "ok" }) }
        assertEquals(1, stops)
        now += 11_000L
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(2, stops)
    }

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

package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `unconfigured SMB does not run preflight or control VPN`() {
        val retry = SmbWireGuardRetry(
            startTunnel = { error("UP is unexpected") },
            stopTunnel = { error("DOWN is unexpected") },
            isReachable = { _, _ -> error("preflight is unexpected") },
            localReachable = { _, _ -> error("Wi-Fi test is unexpected") },
            awaitReachable = { _, _ -> error("VPN wait is unexpected") },
        )
        assertEquals("direct", retry.connect(connection("")) { "direct" })
    }

    @Test
    fun `directly reachable SMB opens without VPN start`() {
        val events = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { events.add("up") },
            isReachable = { _, _ -> events.add("probe"); true },
            awaitReachable = { _, _ -> error("no VPN wait") },
        )
        assertEquals("connected", retry.connect(connection()) {
            events.add("smb")
            "connected"
        })
        assertEquals(listOf("probe", "smb"), events)
    }

    @Test
    fun `unreachable SMB starts VPN before first SMBJ connection`() {
        val events = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { events.add("up:$it") },
            isReachable = { host, port ->
                events.add("probe:$host:$port")
                false
            },
            awaitReachable = { host, port ->
                events.add("await:$host:$port")
                true
            },
        )
        assertEquals("connected", retry.connect(connection()) {
            events.add("smb")
            "connected"
        })
        assertEquals(
            listOf("probe:192.168.1.2:445", "up:home", "await:192.168.1.2:445", "smb"),
            events,
        )
    }

    @Test
    fun `reachable port but SMB protocol fails does not start tunnel`() {
        val failure = IOException("SMB protocol mismatch")
        val retry = SmbWireGuardRetry(
            startTunnel = { error("unexpected VPN UP") },
            isReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("unexpected VPN wait") },
        )
        assertSame(failure, assertThrows(IOException::class.java) {
            retry.connect(connection()) { throw failure }
        })
    }

    @Test
    fun `VPN startup timeout never attempts SMB authentication`() {
        var opens = 0
        var starts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            isReachable = { _, _ -> false },
            awaitReachable = { _, _ -> false },
        )
        val error = assertThrows(IOException::class.java) {
            retry.connect(connection()) {
                opens++
                "should not be reached"
            }
        }
        assertTrue(error.message!!.contains("WireGuard"))
        assertEquals(1, starts)
        assertEquals(0, opens)
    }

    @Test
    fun `SMB authentication after tunnel starts runs once and its failure propagates`() {
        var opens = 0
        var starts = 0
        val authError = IOException("Authentication failed")
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            isReachable = { _, _ -> false },
            awaitReachable = { _, _ -> true },
        )
        assertSame(authError, assertThrows(IOException::class.java) {
            retry.connect(connection()) {
                opens++
                throw authError
            }
        })
        assertEquals(1, starts)
        assertEquals(1, opens)
    }

    @Test
    fun `home Wi-Fi reachability stops configured tunnel without default VPN check`() {
        val events = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { events.add("up") },
            stopTunnel = { events.add("down:$it") },
            localReachable = { host, port ->
                assertEquals("192.168.1.2", host)
                assertEquals(445, port)
                true
            },
            isReachable = { _, _ -> error("physical Wi-Fi success is sufficient") },
            awaitReachable = { _, _ -> error("no VPN wait") },
        )
        assertEquals("connected", retry.connect(connection()) {
            events.add("smb")
            "connected"
        })
        assertEquals(listOf("down:home", "smb"), events)
    }

    @Test
    fun `home Wi-Fi reachable at another subnet still stops tunnel`() {
        var stops = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { error("must not start VPN") },
            stopTunnel = { stops++ },
            localReachable = { _, _ -> true },
            isReachable = { _, _ -> error("no default preflight needed") },
            awaitReachable = { _, _ -> error("no VPN wait") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(1, stops)
    }

    @Test
    fun `no physical Wi-Fi reachability never switches off VPN`() {
        var stops = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { error("VPN already has a route to NAS") },
            stopTunnel = { stops++ },
            localReachable = { _, _ -> false },
            isReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no VPN wait") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(0, stops)
    }

    @Test
    fun `denied DOWN request does not block local SMB`() {
        val retry = SmbWireGuardRetry(
            startTunnel = { error("UP is unexpected") },
            stopTunnel = { throw SecurityException("permission missing") },
            localReachable = { _, _ -> true },
            isReachable = { _, _ -> error("no default preflight") },
            awaitReachable = { _, _ -> error("no wait") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
    }

    @Test
    fun `DOWN request never immediately rebounds into UP after SMB error`() {
        val events = mutableListOf<String>()
        val failure = IOException("routing switching")
        val retry = SmbWireGuardRetry(
            startTunnel = { events.add("up") },
            stopTunnel = { events.add("down") },
            localReachable = { _, _ -> true },
            isReachable = { _, _ -> false },
            awaitReachable = { _, _ -> error("no wait") },
        )
        assertSame(failure, assertThrows(IOException::class.java) {
            retry.connect(connection()) { throw failure }
        })
        assertEquals(listOf("down"), events)
    }

    @Test
    fun `UP and DOWN requests are rate limited per tunnel`() {
        var starts = 0
        var stops = 0
        var now = 100L
        var atHome = false
        val retry = SmbWireGuardRetry(
            startTunnel = { starts++ },
            stopTunnel = { stops++ },
            localReachable = { _, _ -> atHome },
            isReachable = { _, _ -> false },
            awaitReachable = { _, _ -> true },
            nowMillis = { now },
        )
        repeat(2) { assertEquals("ok", retry.connect(connection()) { "ok" }) }
        assertEquals(1, starts)
        atHome = true
        repeat(2) { assertEquals("ok", retry.connect(connection()) { "ok" }) }
        assertEquals(1, stops)
        now += 11_000L
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(2, stops)
        assertEquals(1, starts)
    }

    @Test
    fun `UP permission error surfaces before SMB is attempted`() {
        var opens = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { throw SecurityException("control permission missing") },
            isReachable = { _, _ -> false },
            awaitReachable = { _, _ -> error("should not wait") },
        )
        val failure = assertThrows(SecurityException::class.java) {
            retry.connect(connection()) {
                opens++
                "unexpected"
            }
        }
        assertTrue(failure.message!!.contains("permission"))
        assertEquals(0, opens)
    }
}

package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
    fun `VPN without bypass briefly shuts down to verify matching home Wi-Fi`() {
        val events = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { events.add("UP") },
            stopTunnel = { events.add("DOWN:$it") },
            localReachable = { _, _ -> false },
            hasMatchingWifiSubnet = { host -> host == "192.168.1.2" },
            hasActiveVpn = { true },
            awaitWifiDirect = { _, _ -> events.add("Wi-Fi TCP ok"); true },
            isReachable = { _, _ -> error("existing VPN route must not win") },
            awaitReachable = { _, _ -> error("not a remote tunnel") },
        )
        assertEquals("ok", retry.connect(connection()) { events.add("SMBJ"); "ok" })
        assertEquals(listOf("DOWN:home", "Wi-Fi TCP ok", "SMBJ"), events)
    }

    @Test
    fun `non-matching subnet cannot temporarily stop any VPN`() {
        var stops = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { error("UP unexpected") },
            stopTunnel = { stops++ },
            hasMatchingWifiSubnet = { false },
            hasActiveVpn = { true },
            awaitWifiDirect = { _, _ -> error("no trial") },
            isReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(0, stops)
    }

    @Test
    fun `no running VPN does not trigger a temporary DOWN trial`() {
        var stops = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { error("UP unexpected") },
            stopTunnel = { stops++ },
            hasMatchingWifiSubnet = { true },
            hasActiveVpn = { false },
            awaitWifiDirect = { _, _ -> error("no trial") },
            isReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(0, stops)
    }

    @Test
    fun `failed LAN cutover restores the previously connected VPN and waits before SMBJ`() {
        val events = mutableListOf<String>()
        var vpnActive = true
        val retry = SmbWireGuardRetry(
            startTunnel = { events.add("UP"); vpnActive = true },
            stopTunnel = { events.add("DOWN"); vpnActive = false },
            hasMatchingWifiSubnet = { true },
            hasActiveVpn = { vpnActive },
            awaitWifiDirect = { _, _ -> events.add("LAN timeout"); false },
            isReachable = { _, _ -> error("should restore before default route") },
            awaitReachable = { _, _ -> events.add("VPN reachable"); true },
        )
        assertEquals("ok", retry.connect(connection()) { events.add("SMBJ"); "ok" })
        assertEquals(listOf("DOWN", "LAN timeout", "UP", "VPN reachable", "SMBJ"), events)
    }

    @Test
    fun `ignored DOWN does not start a different VPN and preserves the original route`() {
        val events = mutableListOf<String>()
        val retry = SmbWireGuardRetry(
            startTunnel = { error("must not turn on WireGuard when another VPN remains") },
            stopTunnel = { events.add("DOWN attempted") },
            hasMatchingWifiSubnet = { true },
            hasActiveVpn = { true },
            awaitWifiDirect = { _, _ -> events.add("LAN failed"); false },
            isReachable = { _, _ -> events.add("original route works"); true },
            awaitReachable = { _, _ -> error("no UP retry") },
        )
        assertEquals("ok", retry.connect(connection()) { events.add("SMBJ"); "ok" })
        assertEquals(listOf("DOWN attempted", "LAN failed", "original route works", "SMBJ"), events)
    }

    @Test
    fun `failed DOWN permission is not masked and does not restart tunnel`() {
        var attempts = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { error("unexpected UP") },
            stopTunnel = { attempts++; throw SecurityException("permission missing") },
            hasMatchingWifiSubnet = { true },
            hasActiveVpn = { true },
            awaitWifiDirect = { _, _ -> error("no LAN wait") },
            isReachable = { _, _ -> true },
            awaitReachable = { _, _ -> error("no retry") },
        )
        assertEquals("ok", retry.connect(connection()) { "ok" })
        assertEquals(1, attempts)
    }

    @Test
    fun `VPN restoration error never sends SMB credentials`() {
        var vpnActive = true
        var smbOpens = 0
        val retry = SmbWireGuardRetry(
            startTunnel = { throw SecurityException("control revoked") },
            stopTunnel = { vpnActive = false },
            hasMatchingWifiSubnet = { true },
            hasActiveVpn = { vpnActive },
            awaitWifiDirect = { _, _ -> false },
            isReachable = { _, _ -> error("no default route") },
            awaitReachable = { _, _ -> error("should not poll") },
        )
        assertThrows(SecurityException::class.java) {
            retry.connect(connection()) { smbOpens++; "wrong" }
        }
        assertEquals(0, smbOpens)
    }

    @Test
    fun `concurrent SMB connects cannot bring VPN UP during a LAN cutover`() {
        val vpnActive = AtomicBoolean(true)
        val inLanWait = CountDownLatch(1)
        val allowWifi = CountDownLatch(1)
        val operations = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>(null)
        val retry = SmbWireGuardRetry(
            stopTunnel = { operations.add("DOWN") },
            startTunnel = { operations.add("UP") },
            localReachable = { _, _ -> !vpnActive.get() },
            hasMatchingWifiSubnet = { true },
            hasActiveVpn = { vpnActive.get() },
            awaitWifiDirect = { _, _ ->
                inLanWait.countDown()
                val released = allowWifi.await(3, TimeUnit.SECONDS)
                vpnActive.set(false)
                released
            },
            isReachable = { _, _ -> false },
            awaitReachable = { _, _ -> true },
        )
        val first = Thread {
            try { retry.connect(connection()) { "ok" } } catch (e: Throwable) { failure.set(e) }
        }
        val second = Thread {
            try { retry.connect(connection()) { "ok" } } catch (e: Throwable) { failure.set(e) }
        }
        first.start()
        assertTrue(inLanWait.await(3, TimeUnit.SECONDS))
        second.start()
        Thread.sleep(100)
        allowWifi.countDown()
        first.join(3000)
        second.join(3000)
        assertEquals(null, failure.get())
        assertEquals(listOf("DOWN"), operations.toList())
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

package app.local1st.files.core.fs

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbWifiSubnetTest {
    private fun addr(value: String): InetAddress = InetAddress.getByName(value)

    @Test
    fun `same 24-bit Wi-Fi subnet allows a NAS probe`() {
        assertTrue(isAddressInSubnet(addr("192.168.10.7"), addr("192.168.10.120"), 24))
        assertFalse(isAddressInSubnet(addr("192.168.11.7"), addr("192.168.10.120"), 24))
    }

    @Test
    fun `23-bit Wi-Fi prefix includes the adjacent address block`() {
        assertTrue(isAddressInSubnet(addr("192.168.11.7"), addr("192.168.10.120"), 23))
        assertFalse(isAddressInSubnet(addr("192.168.12.7"), addr("192.168.10.120"), 23))
    }

    @Test
    fun `Wi-Fi prefix must be the actual prefix and cannot be silently assumed`() {
        assertTrue(isAddressInSubnet(addr("10.1.2.17"), addr("10.1.1.5"), 16))
        assertFalse(isAddressInSubnet(addr("10.1.2.17"), addr("10.1.1.5"), 24))
    }

    @Test
    fun `IPv6 and IPv4 addresses cannot share a subnet`() {
        assertFalse(isAddressInSubnet(addr("192.168.10.7"), addr("2001:db8::1"), 64))
        assertFalse(isAddressInSubnet(addr("2001:db8::1"), addr("192.168.10.7"), 24))
        assertTrue(isAddressInSubnet(addr("2001:db8:1::7"), addr("2001:db8:1::10"), 64))
        assertFalse(isAddressInSubnet(addr("2001:db8:2::7"), addr("2001:db8:1::10"), 64))
    }

    @Test
    fun `invalid prefixes cannot mark every host as local`() {
        assertFalse(isAddressInSubnet(addr("192.168.10.7"), addr("192.168.10.10"), 0))
        assertFalse(isAddressInSubnet(addr("192.168.10.7"), addr("192.168.10.10"), -1))
        assertFalse(isAddressInSubnet(addr("192.168.10.7"), addr("192.168.10.10"), 33))
        assertTrue(isAddressInSubnet(addr("192.168.10.10"), addr("192.168.10.10"), 32))
        assertFalse(isAddressInSubnet(addr("192.168.10.11"), addr("192.168.10.10"), 32))
    }

    @Test
    fun `only addresses in the Wi-Fi IP range may be probed`() {
        val hosts = listOf(addr("192.168.11.7"), addr("192.168.10.7"), addr("2001:db8::7"))
        val wifi = listOf(addr("192.168.10.120") to 24)
        assertEquals(listOf(addr("192.168.10.7")), matchingWifiSubnetAddresses(hosts, wifi))
    }

    @Test
    fun `nothing is probed when phone is not on a matching Wi-Fi range`() {
        val hosts = listOf(addr("10.0.0.2"), addr("192.168.11.7"))
        val wifi = listOf(addr("192.168.10.120") to 24)
        assertTrue(matchingWifiSubnetAddresses(hosts, wifi).isEmpty())
        assertTrue(matchingWifiSubnetAddresses(hosts, emptyList()).isEmpty())
    }
}

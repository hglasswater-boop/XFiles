package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbReadBackendTest {
    @Test fun autoChecksJniThenPreparesVpnBeforeRustOpen() {
        val calls = mutableListOf<String>()
        val rust = ByteArrayInputStream(byteArrayOf(1))
        val selected = SmbReadBackend.route(
            mode = "auto",
            ensureNative = { calls += "JNI" },
            prepareNetwork = { calls += "VPN" },
            rust = { calls += "RUST"; rust },
            smbj = { calls += "SMBJ"; ByteArrayInputStream(byteArrayOf()) },
        )
        assertSame(rust, selected)
        assertEquals(listOf("JNI", "VPN", "RUST"), calls)
    }

    @Test fun strictRustNeverFallsBackOnOpenFailure() {
        val calls = mutableListOf<String>()
        val error = IOException("remote error")
        assertSame(error, assertThrows(IOException::class.java) {
            SmbReadBackend.route(
                mode = "rust",
                ensureNative = { calls += "JNI" },
                prepareNetwork = { calls += "VPN" },
                rust = { calls += "RUST"; throw error },
                smbj = { calls += "SMBJ"; ByteArrayInputStream(byteArrayOf()) },
            )
        })
        assertEquals(listOf("JNI", "VPN", "RUST"), calls)
    }

    @Test fun autoFallbackOnlyIfJniUnavailableBeforeOpening() {
        val calls = mutableListOf<String>()
        val smbj = ByteArrayInputStream(byteArrayOf(9))
        val selected = SmbReadBackend.route(
            mode = "auto",
            ensureNative = { calls += "JNI"; throw RustSmbUnavailableException("not loaded") },
            prepareNetwork = { calls += "VPN" },
            rust = { calls += "RUST"; ByteArrayInputStream(byteArrayOf()) },
            smbj = { calls += "SMBJ"; smbj },
        )
        assertSame(smbj, selected)
        assertEquals(listOf("JNI", "SMBJ"), calls)
    }

    @Test fun autoDoesNotFallbackAfterNativeOpenHasStarted() {
        val calls = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            SmbReadBackend.route(
                mode = "auto",
                ensureNative = { calls += "JNI" },
                prepareNetwork = { calls += "VPN" },
                rust = { calls += "RUST"; throw IOException("signed read open failed") },
                smbj = { calls += "SMBJ"; ByteArrayInputStream(byteArrayOf()) },
            )
        }
        assertEquals(listOf("JNI", "VPN", "RUST"), calls)
    }

    @Test fun explicitSmbjNeverTouchesRustOrPreflight() {
        val calls = mutableListOf<String>()
        val smbj = ByteArrayInputStream(byteArrayOf(5))
        val selected = SmbReadBackend.route(
            mode = "smbj",
            ensureNative = { error("Unexpected JNI") },
            prepareNetwork = { error("Unexpected VPN preflight") },
            rust = { error("Unexpected Rust") },
            smbj = { calls += "SMBJ"; smbj },
        )
        assertSame(smbj, selected)
        assertEquals(listOf("SMBJ"), calls)
    }

    @Test fun vpnFailureDoesNotOpenRustOrFallback() {
        val calls = mutableListOf<String>()
        assertThrows(IOException::class.java) {
            SmbReadBackend.route(
                mode = "auto",
                ensureNative = { calls += "JNI" },
                prepareNetwork = { calls += "VPN"; throw IOException("route") },
                rust = { calls += "RUST"; ByteArrayInputStream(byteArrayOf()) },
                smbj = { calls += "SMBJ"; ByteArrayInputStream(byteArrayOf()) },
            )
        }
        assertEquals(listOf("JNI", "VPN"), calls)
    }
}

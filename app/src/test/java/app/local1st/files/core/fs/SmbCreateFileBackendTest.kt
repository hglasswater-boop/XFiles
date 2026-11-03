package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbCreateFileBackendTest {
    @Test
    fun autoChecksNativeThenPreparesNetworkThenCreatesOnce() {
        val steps = mutableListOf<String>()
        SmbCreateFileBackend.route(
            mode = "auto",
            ensureNative = { steps += "JNI" },
            prepareNetwork = { steps += "VPN" },
            rust = { steps += "FILE_CREATE" },
            smbj = { steps += "SMBJ" },
        )
        assertEquals(listOf("JNI", "VPN", "FILE_CREATE"), steps)
    }

    @Test
    fun autoFallsBackOnlyWhenNativeIsMissingBeforeFileCreation() {
        val steps = mutableListOf<String>()
        SmbCreateFileBackend.route(
            mode = "auto",
            ensureNative = { throw RustSmbUnavailableException("missing JNI") },
            prepareNetwork = { steps += "VPN" },
            rust = { steps += "FILE_CREATE" },
            smbj = { steps += "SMBJ" },
        )
        assertEquals(listOf("SMBJ"), steps)
    }

    @Test
    fun failedCreateDoesNotRetryThroughSmbj() {
        val error = IOException("CREATE outcome unknown after disconnect")
        val steps = mutableListOf<String>()
        val actual = assertThrows(IOException::class.java) {
            SmbCreateFileBackend.route(
                mode = "auto",
                ensureNative = { steps += "JNI" },
                prepareNetwork = { steps += "VPN" },
                rust = { steps += "FILE_CREATE"; throw error },
                smbj = { steps += "SMBJ" },
            )
        }
        assertSame(error, actual)
        assertEquals(listOf("JNI", "VPN", "FILE_CREATE"), steps)
    }

    @Test
    fun vpnErrorAbortsBeforeFileCreate() {
        val error = IOException("SMB host unreachable")
        val steps = mutableListOf<String>()
        assertSame(error, assertThrows(IOException::class.java) {
            SmbCreateFileBackend.route(
                mode = "auto",
                ensureNative = { steps += "JNI" },
                prepareNetwork = { steps += "VPN"; throw error },
                rust = { steps += "FILE_CREATE" },
                smbj = { steps += "SMBJ" },
            )
        })
        assertEquals(listOf("JNI", "VPN"), steps)
    }

    @Test
    fun explicitSmbjDoesNotLoadRustOrChangeVpnRoute() {
        val steps = mutableListOf<String>()
        SmbCreateFileBackend.route(
            mode = "smbj",
            ensureNative = { error("unexpected native load") },
            prepareNetwork = { error("unexpected VPN") },
            rust = { error("unexpected Rust") },
            smbj = { steps += "SMBJ" },
        )
        assertEquals(listOf("SMBJ"), steps)
    }

    @Test
    fun strictRustMissingJniNeverFallsBack() {
        var smbjCalls = 0
        assertThrows(RustSmbUnavailableException::class.java) {
            SmbCreateFileBackend.route(
                mode = "rust",
                ensureNative = { throw RustSmbUnavailableException("missing") },
                prepareNetwork = {},
                rust = {},
                smbj = { smbjCalls++ },
            )
        }
        assertEquals(0, smbjCalls)
    }
}

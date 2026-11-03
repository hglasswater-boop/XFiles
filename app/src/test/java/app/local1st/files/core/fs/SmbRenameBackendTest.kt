package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbRenameBackendTest {
    @Test
    fun autoChecksNativeThenPreparesNetworkThenRenamesExactlyOnce() {
        val steps = mutableListOf<String>()
        SmbRenameBackend.route(
            mode = "auto",
            ensureRustAvailable = { steps += "JNI" },
            prepareNetwork = { steps += "WireGuard" },
            rust = { steps += "rename" },
            smbj = { steps += "SMBJ" },
        )
        assertEquals(listOf("JNI", "WireGuard", "rename"), steps)
    }

    @Test
    fun missingNativeInAutoFallsBackBeforeNetworkPreparationOrMutation() {
        val steps = mutableListOf<String>()
        SmbRenameBackend.route(
            mode = "auto",
            ensureRustAvailable = { throw RustSmbUnavailableException("JNI unavailable") },
            prepareNetwork = { steps += "WireGuard" },
            rust = { steps += "rename" },
            smbj = { steps += "SMBJ" },
        )
        assertEquals(listOf("SMBJ"), steps)
    }

    @Test
    fun failedRustRenameDoesNotRepeatMutationThroughSmbj() {
        val failure = IOException("rename response lost")
        val steps = mutableListOf<String>()
        val observed = assertThrows(IOException::class.java) {
            SmbRenameBackend.route(
                mode = "auto",
                ensureRustAvailable = { steps += "JNI" },
                prepareNetwork = { steps += "WireGuard" },
                rust = { steps += "rename"; throw failure },
                smbj = { steps += "SMBJ" },
            )
        }
        assertSame(failure, observed)
        assertEquals(listOf("JNI", "WireGuard", "rename"), steps)
    }

    @Test
    fun failedPreflightDoesNotStartRenameOrFallback() {
        val failure = IOException("WireGuard unreachable")
        val steps = mutableListOf<String>()
        val observed = assertThrows(IOException::class.java) {
            SmbRenameBackend.route(
                mode = "auto",
                ensureRustAvailable = { steps += "JNI" },
                prepareNetwork = { steps += "WireGuard"; throw failure },
                rust = { steps += "rename" },
                smbj = { steps += "SMBJ" },
            )
        }
        assertSame(failure, observed)
        assertEquals(listOf("JNI", "WireGuard"), steps)
    }

    @Test
    fun explicitSmbjNeverTouchesNativeOrNetworkPreflight() {
        val steps = mutableListOf<String>()
        SmbRenameBackend.route(
            mode = "smbj",
            ensureRustAvailable = { steps += "JNI" },
            prepareNetwork = { steps += "WireGuard" },
            rust = { steps += "rename" },
            smbj = { steps += "SMBJ" },
        )
        assertEquals(listOf("SMBJ"), steps)
    }

    @Test
    fun explicitRustNeverFallsBackWhenNativeIsUnavailable() {
        val steps = mutableListOf<String>()
        assertThrows(RustSmbUnavailableException::class.java) {
            SmbRenameBackend.route(
                mode = "rust",
                ensureRustAvailable = { throw RustSmbUnavailableException("missing") },
                prepareNetwork = { steps += "WireGuard" },
                rust = { steps += "rename" },
                smbj = { steps += "SMBJ" },
            )
        }
        assertEquals(emptyList<String>(), steps)
    }
}

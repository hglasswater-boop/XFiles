package app.local1st.files.core.fs

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbWriteBackendTest {
    @Test
    fun autoRemainsOnExistingPipelinedSmbjUntilPerformanceGate() {
        val steps = mutableListOf<String>()
        val smbj = ByteArrayOutputStream()
        val actual = SmbWriteBackend.route(
            mode = "auto",
            ensureNative = { steps += "JNI" },
            prepareNetwork = { steps += "VPN" },
            rust = { steps += "RUST"; ByteArrayOutputStream() },
            smbj = { steps += "SMBJ"; smbj },
        )
        assertSame(smbj, actual)
        assertEquals(listOf("SMBJ"), steps)
    }

    @Test
    fun explicitRustPreparesNetworkBeforeOverwritingAndDoesNotFallback() {
        val steps = mutableListOf<String>()
        val expected = ByteArrayOutputStream()
        val actual = SmbWriteBackend.route(
            mode = "rust",
            ensureNative = { steps += "JNI" },
            prepareNetwork = { steps += "VPN" },
            rust = { steps += "RUST"; expected },
            smbj = { steps += "SMBJ"; ByteArrayOutputStream() },
        )
        assertSame(expected, actual)
        assertEquals(listOf("JNI", "VPN", "RUST"), steps)
    }

    @Test
    fun rustOpenFailureIsNeverReplayedOnSmbj() {
        val failure = IOException("OVERWRITE_IF ambiguous")
        val steps = mutableListOf<String>()
        val seen = assertThrows(IOException::class.java) {
            SmbWriteBackend.route(
                mode = "rust",
                ensureNative = { steps += "JNI" },
                prepareNetwork = { steps += "VPN" },
                rust = { steps += "RUST"; throw failure },
                smbj = { steps += "SMBJ"; ByteArrayOutputStream() },
            )
        }
        assertSame(failure, seen)
        assertEquals(listOf("JNI", "VPN", "RUST"), steps)
    }

    @Test
    fun smbjDoesNotLoadJniOrPrepareNetworkInRustAdapter() {
        val smbj = ByteArrayOutputStream()
        assertSame(smbj, SmbWriteBackend.route(
            mode = "smbj",
            ensureNative = { error("native unexpected") },
            prepareNetwork = { error("VPN unexpected") },
            rust = { error("Rust unexpected") },
            smbj = { smbj },
        ))
    }
}

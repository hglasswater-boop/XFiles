package app.local1st.files.core.fs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbRandomEditRoutingTest {
    @Test
    fun autoKeepsSmbjForRandomEditsUntilRealNasParity() {
        val steps = mutableListOf<String>()
        val smbj = Any()
        val result = SmbRandomEditRouting.route(
            mode = "auto",
            ensureNative = { steps += "JNI" },
            cleanup = { steps += "CLEANUP" },
            rust = { steps += "RUST"; Any() },
            smbj = { steps += "SMBJ"; smbj },
        )
        assertSame(smbj, result)
        assertEquals(listOf("SMBJ"), steps)
    }

    @Test
    fun rustChecksVersionThenCleansStalePartialsBeforeOpeningFile() {
        val steps = mutableListOf<String>()
        val rust = Any()
        val actual = SmbRandomEditRouting.route(
            mode = "rust",
            ensureNative = { steps += "JNI" },
            cleanup = { steps += "CLEANUP" },
            rust = { steps += "RUST"; rust },
            smbj = { steps += "SMBJ"; Any() },
        )
        assertSame(rust, actual)
        assertEquals(listOf("JNI", "CLEANUP", "RUST"), steps)
    }

    @Test
    fun rustOpenFailureNeverFallsBackToSmbj() {
        val fail = IOException("SMB FILE_OPEN failed")
        val steps = mutableListOf<String>()
        val error = assertThrows(IOException::class.java) {
            SmbRandomEditRouting.route(
                mode = "rust",
                ensureNative = { steps += "JNI" },
                cleanup = { steps += "CLEANUP" },
                rust = { steps += "RUST"; throw fail },
                smbj = { steps += "SMBJ"; Any() },
            )
        }
        assertSame(fail, error)
        assertEquals(listOf("JNI", "CLEANUP", "RUST"), steps)
    }

    @Test
    fun smbjDoesNotRequireNativeLibraryOrSeparateCleanup() {
        val smbj = Any()
        assertSame(
            smbj,
            SmbRandomEditRouting.route(
                mode = "smbj",
                ensureNative = { error("JNI not expected") },
                cleanup = { error("duplicate cleanup") },
                rust = { error("Rust not expected") },
                smbj = { smbj },
            ),
        )
    }
}

package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbMkdirBackendTest {
    @Test
    fun autoUsesRustAfterNativeCompatibilityCheck() {
        val events = mutableListOf<String>()
        SmbMkdirBackend.route(
            mode = "auto",
            ensureRustAvailable = { events += "native" },
            rust = { events += "rust mkdir" },
            smbj = { events += "smbj mkdir" },
        )
        assertEquals(listOf("native", "rust mkdir"), events)
    }

    @Test
    fun autoFallsBackOnlyWhenNativeIsUnavailableBeforeMutation() {
        val events = mutableListOf<String>()
        SmbMkdirBackend.route(
            mode = "auto",
            ensureRustAvailable = {
                events += "native unavailable"
                throw RustSmbUnavailableException("missing JNI")
            },
            rust = { events += "rust mkdir" },
            smbj = { events += "smbj mkdir" },
        )
        assertEquals(listOf("native unavailable", "smbj mkdir"), events)
    }

    @Test
    fun autoDoesNotRepeatAmbiguousRustMutationWithSmbj() {
        val failure = IOException("mutation outcome unknown")
        val events = mutableListOf<String>()
        val actual = assertThrows(IOException::class.java) {
            SmbMkdirBackend.route(
                mode = "auto",
                ensureRustAvailable = { events += "native" },
                rust = { events += "rust mkdir"; throw failure },
                smbj = { events += "smbj mkdir" },
            )
        }
        assertSame(failure, actual)
        assertEquals(listOf("native", "rust mkdir"), events)
    }

    @Test
    fun autoDoesNotFallbackForNonAvailabilityErrorsInNativeCheck() {
        val failure = IOException("bad configuration")
        var calls = 0
        val actual = assertThrows(IOException::class.java) {
            SmbMkdirBackend.route(
                mode = "auto",
                ensureRustAvailable = { throw failure },
                rust = { calls++ },
                smbj = { calls++ },
            )
        }
        assertSame(failure, actual)
        assertEquals(0, calls)
    }

    @Test
    fun explicitSmbjNeverLoadsNativeOrPreparesRustConnection() {
        val events = mutableListOf<String>()
        SmbMkdirBackend.route(
            mode = "smbj",
            ensureRustAvailable = { events += "native" },
            rust = { events += "rust mkdir" },
            smbj = { events += "smbj mkdir" },
        )
        assertEquals(listOf("smbj mkdir"), events)
    }

    @Test
    fun strictRustNeverFallsBackWhenNativeMissing() {
        val failure = RustSmbUnavailableException("missing JNI")
        val events = mutableListOf<String>()
        val actual = assertThrows(RustSmbUnavailableException::class.java) {
            SmbMkdirBackend.route(
                mode = "rust",
                ensureRustAvailable = { throw failure },
                rust = { events += "rust mkdir" },
                smbj = { events += "smbj mkdir" },
            )
        }
        assertSame(failure, actual)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun strictRustLoadsBeforeExecutingTheMutation() {
        val events = mutableListOf<String>()
        SmbMkdirBackend.route(
            mode = "rust",
            ensureRustAvailable = { events += "native" },
            rust = { events += "rust mkdir" },
            smbj = { events += "smbj mkdir" },
        )
        assertEquals(listOf("native", "rust mkdir"), events)
    }
}

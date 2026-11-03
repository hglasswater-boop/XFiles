package app.local1st.files.core.fs

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbMkdirBackendTest {
    @Test
    fun strictRustRoutesOnlyToNative() {
        var rustCalls = 0
        var smbjCalls = 0

        SmbMkdirBackend.route("rust", rust = { rustCalls++ }, smbj = { smbjCalls++ })

        assertEquals(1, rustCalls)
        assertEquals(0, smbjCalls)
    }

    @Test
    fun autoAndExplicitSmbjDoNotUseUnverifiedNativeMutation() {
        for (mode in listOf("auto", "smbj")) {
            var rustCalls = 0
            var smbjCalls = 0

            SmbMkdirBackend.route(mode, rust = { rustCalls++ }, smbj = { smbjCalls++ })

            assertEquals(0, rustCalls)
            assertEquals(1, smbjCalls)
        }
    }

    @Test
    fun failedNativeMutationNeverRetriesThroughSmbj() {
        val failure = IOException("mutation outcome unknown")
        var fallbackCalls = 0

        val actual = assertThrows(IOException::class.java) {
            SmbMkdirBackend.route(
                "rust",
                rust = { throw failure },
                smbj = { fallbackCalls++ },
            )
        }

        assertSame(failure, actual)
        assertEquals(0, fallbackCalls)
    }
}

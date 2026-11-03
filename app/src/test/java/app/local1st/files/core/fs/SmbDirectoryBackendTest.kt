package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbUnavailableException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbDirectoryBackendTest {
    @Test
    fun autoUsesRustAndNeverFallsBackAfterQueryFailure() {
        val failure = IOException("partial directory query failed")
        var smbjCalls = 0
        val seen = assertThrows(IOException::class.java) {
            SmbDirectoryBackend.route(
                mode = "auto",
                ensureNative = {},
                rust = { throw failure },
                smbj = { smbjCalls++; emptyList<String>() },
            )
        }
        assertSame(failure, seen)
        assertEquals(0, smbjCalls)
    }

    @Test
    fun autoUsesSmbjOnlyIfNativeIsUnavailableBeforeOpen() {
        val result = SmbDirectoryBackend.route(
            mode = "auto",
            ensureNative = { throw RustSmbUnavailableException("not packaged") },
            rust = { error("native should not execute") },
            smbj = { listOf("SMBJ") },
        )
        assertEquals(listOf("SMBJ"), result)
    }

    @Test
    fun explicitSmbjNeverChecksNative() {
        val result = SmbDirectoryBackend.route(
            mode = "smbj",
            ensureNative = { error("unexpected JNI") },
            rust = { error("unexpected Rust") },
            smbj = { listOf("SMBJ") },
        )
        assertEquals(listOf("SMBJ"), result)
    }

    @Test
    fun multiplePagesAreCollectedAndCursorAlwaysCloses() {
        val steps = mutableListOf<String>()
        val result = SmbDirectoryBackend.collectPages(
            open = { steps += "open"; 7L },
            read = { handle ->
                steps += "read"
                if (steps.count { it == "read" } == 1) dataPage("one")
                else if (steps.count { it == "read" } == 2) dataPage("two")
                else dataPage()
            },
            close = { handle -> steps += "close $handle" },
            map = { it.name },
        )
        assertEquals(listOf("one", "two"), result)
        assertEquals(listOf("open", "read", "read", "read", "close 7"), steps)
    }

    @Test
    fun pageFailureClosesCursorAndDoesNotPresentPartialList() {
        val steps = mutableListOf<String>()
        val failure = IOException("query failed")
        val seen = assertThrows(IOException::class.java) {
            SmbDirectoryBackend.collectPages(
                open = { steps += "open"; 5L },
                read = {
                    steps += "read"
                    if (steps.count { s -> s == "read" } == 1) dataPage("one")
                    else throw failure
                },
                close = { steps += "close" },
                map = { it.name },
            )
        }
        assertSame(failure, seen)
        assertEquals(listOf("open", "read", "read", "close"), steps)
    }

    private fun dataPage(vararg names: String): ByteArray {
        val size = 8 + names.sumOf { 32 + it.toByteArray(Charsets.UTF_8).size }
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("XFL1".toByteArray(Charsets.US_ASCII))
        out.putInt(names.size)
        names.forEach {
            val name = it.toByteArray(Charsets.UTF_8)
            out.putInt(name.size)
            out.put(name)
            out.putInt(0)
            out.putLong(1)
            out.putLong(116_444_736_000_000_000L)
            out.putLong(116_444_736_000_000_000L)
        }
        return out.array()
    }
}

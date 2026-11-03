package app.local1st.files.core.fs.rust

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RustSmbOutputStreamTest {
    @Test
    fun largeWriteIsChunkedToOneMibAndPositionsContinue() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val bytes = ByteArray(1024 * 1024 + 5) { (it % 251).toByte() }
        val out = stream(
            write = { _, position, _, _, length ->
                calls += position to length
                length
            },
        )
        out.write(bytes)
        out.close()
        assertEquals(listOf(0L to (1024 * 1024), (1024L * 1024L) to 5), calls)
    }

    @Test
    fun validatesRangeBeforeMutatingAndZeroByteWriteIsNoop() {
        var writes = 0
        val out = stream(write = { _, _, _, _, length -> writes++; length })
        assertThrows(IndexOutOfBoundsException::class.java) {
            out.write(byteArrayOf(1, 2), 1, 2)
        }
        out.write(byteArrayOf(1, 2), 2, 0)
        assertEquals(0, writes)
        out.close()
    }

    @Test
    fun explicitFlushAndCloseBothCommitOnSameHandle() {
        val steps = mutableListOf<String>()
        val out = stream(
            write = { handle, position, _, offset, length ->
                steps += "write $handle $position $offset $length"
                length
            },
            flush = { steps += "flush $it" },
            close = { steps += "close $it" },
        )
        out.write(byteArrayOf(7, 8), 0, 2)
        out.flush()
        out.close()
        out.close()
        assertEquals(listOf("write 7 0 0 2", "flush 7", "flush 7", "close 7"), steps)
        assertThrows(IOException::class.java) { out.write(1) }
    }

    @Test
    fun failedWritePoisonedAndCloseSkipsFlushButReleasesHandle() {
        val error = IOException("SMB WRITE ambiguous")
        val steps = mutableListOf<String>()
        val out = stream(
            write = { _, _, _, _, _ -> steps += "WRITE"; throw error },
            flush = { steps += "FLUSH" },
            close = { steps += "CLOSE" },
        )
        assertSame(error, assertThrows(IOException::class.java) { out.write(4) })
        assertThrows(IOException::class.java) { out.write(4) }
        assertThrows(IOException::class.java) { out.flush() }
        out.close()
        assertEquals(listOf("WRITE", "CLOSE"), steps)
    }

    @Test
    fun shortWriteIsErrorAndMustNotBeReplayed() {
        var calls = 0
        val out = stream(write = { _, _, _, _, length -> calls++; length - 1 })
        assertThrows(IOException::class.java) { out.write(byteArrayOf(1, 2, 3)) }
        assertThrows(IOException::class.java) { out.flush() }
        out.close()
        assertEquals(1, calls)
    }

    @Test
    fun failedFlushStillClosesAndPreservesSuppressedCloseError() {
        val flushFailure = IOException("SMB FLUSH failed")
        val closeFailure = IOException("SMB CLOSE failed")
        val out = stream(
            flush = { throw flushFailure },
            close = { throw closeFailure },
        )
        val seen = assertThrows(IOException::class.java) { out.close() }
        assertSame(flushFailure, seen)
        assertTrue(seen.suppressed.contains(closeFailure))
    }

    private fun stream(
        write: (Long, Long, ByteArray, Int, Int) -> Int = { _, _, _, _, count -> count },
        flush: (Long) -> Unit = {},
        close: (Long) -> Unit = {},
    ) = RustSmbOutputStream(
        handle = 7L,
        writeNative = write,
        flushNative = flush,
        closeNative = close,
    )
}

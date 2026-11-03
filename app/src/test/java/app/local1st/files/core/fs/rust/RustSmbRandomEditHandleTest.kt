package app.local1st.files.core.fs.rust

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RustSmbRandomEditHandleTest {
    @Test
    fun readLargeRequestInBoundedChunksAndPreserveCallerOffsets() {
        val calls = mutableListOf<Triple<Long, Int, Int>>()
        val handle = open(read = { _, position, _, offset, length ->
            calls += Triple(position, offset, length)
            length
        })
        val length = 1024 * 1024 + 5
        assertEquals(length, handle.read(9, ByteArray(length), 0, length))
        handle.close()
        assertEquals(
            listOf(Triple(9L, 0, 1024 * 1024), Triple(9L + 1024 * 1024, 1024 * 1024, 5)),
            calls,
        )
    }

    @Test
    fun readEofAndShortReadRemainDistinctFromErrors() {
        val eof = open(read = { _, _, _, _, _ -> -1 })
        assertEquals(-1, eof.read(0, ByteArray(2), 0, 2))
        eof.close()
        val partial = open(read = { _, _, _, _, _ -> 1 })
        assertEquals(1, partial.read(0, ByteArray(7), 0, 7))
        partial.close()
    }

    @Test
    fun writeSplitsChunksWithoutAmbiguousRetry() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val handle = open(write = { _, position, _, _, length ->
            calls += position to length
            length
        })
        val length = 1024 * 1024 + 7
        assertEquals(length, handle.write(14, ByteArray(length), 0, length))
        handle.close()
        assertEquals(listOf(14L to (1024 * 1024), 14L + 1024 * 1024 to 7), calls)
    }

    @Test
    fun shortWritePoisonsHandleAndCloseStillReleasesIt() {
        val steps = mutableListOf<String>()
        val handle = open(
            write = { _, _, _, _, size -> steps += "WRITE"; size - 1 },
            flush = { steps += "FLUSH" },
            close = { steps += "CLOSE" },
        )
        assertThrows(IOException::class.java) { handle.write(0, byteArrayOf(1, 2), 0, 2) }
        assertThrows(IOException::class.java) { handle.flush() }
        assertThrows(IOException::class.java) { handle.read(0, ByteArray(1), 0, 1) }
        handle.close()
        handle.close()
        assertEquals(listOf("WRITE", "CLOSE"), steps)
    }

    @Test
    fun failedSetLengthPreventsLaterMutationsButAllowsClose() {
        val failure = IOException("SET_INFO failed")
        val steps = mutableListOf<String>()
        val handle = open(
            setLength = { _, _ -> steps += "SET_INFO"; throw failure },
            close = { steps += "CLOSE" },
        )
        assertSame(failure, assertThrows(IOException::class.java) { handle.setLength(100) })
        assertThrows(IOException::class.java) { handle.write(0, byteArrayOf(3), 0, 1) }
        handle.close()
        assertEquals(listOf("SET_INFO", "CLOSE"), steps)
    }

    @Test
    fun validatesRangesAndLengthWithoutNativeMutation() {
        var writes = 0
        val handle = open(write = { _, _, _, _, n -> writes++; n })
        assertThrows(IndexOutOfBoundsException::class.java) {
            handle.write(0, byteArrayOf(1), 1, 1)
        }
        assertThrows(IllegalArgumentException::class.java) { handle.setLength(-1) }
        assertEquals(0, handle.write(0, byteArrayOf(1), 1, 0))
        assertEquals(0, writes)
        handle.close()
    }

    private fun open(
        read: (Long, Long, ByteArray, Int, Int) -> Int = { _, _, _, _, length -> length },
        write: (Long, Long, ByteArray, Int, Int) -> Int = { _, _, _, _, length -> length },
        setLength: (Long, Long) -> Unit = { _, _ -> },
        flush: (Long) -> Unit = {},
        close: (Long) -> Unit = {},
    ) = RustSmbRandomEditHandle(
        handle = 17L,
        readNative = read,
        writeNative = write,
        setLengthNative = setLength,
        flushNative = flush,
        closeNative = close,
    )
}

package app.local1st.files.core.fs

import java.io.BufferedInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbReadInputStreamTest {
    @Test fun positionalReadsAdvanceOnlyByReturnedBytesAndBoundNativeRequests() {
        val calls = mutableListOf<Pair<Long, Int>>()
        val handle = fake(read = { position, _, _, length ->
            calls += position to length
            length
        })
        val stream = SmbReadInputStream(handle)
        val buffer = ByteArray(1024 * 1024 + 16)
        assertEquals(1024 * 1024, stream.read(buffer, 0, buffer.size))
        assertEquals(16, stream.read(buffer, 0, 16))
        assertEquals(listOf(0L to (1024 * 1024), 1024L * 1024 to 16), calls)
        stream.close()
    }

    @Test fun shortReadEofAndZeroLengthHaveDistinctSemantics() {
        val positions = mutableListOf<Long>()
        val stream = SmbReadInputStream(fake(read = { position, buffer, offset, _ ->
            positions += position
            if (position == 0L) {
                buffer[offset] = 42
                1
            } else -1
        }))
        assertEquals(0, stream.read(ByteArray(0), 0, 0))
        assertEquals(1, stream.read(ByteArray(10), 0, 10))
        assertEquals(-1, stream.read(ByteArray(1), 0, 1))
        assertEquals(listOf(0L, 1L), positions)
        stream.close()
    }

    @Test fun bufferingAvoidsOneNativeReadPerSingleByte() {
        var calls = 0
        val source = SmbReadInputStream(fake(read = { _, buffer, offset, length ->
            calls++
            repeat(length) { buffer[offset + it] = it.toByte() }
            length
        }))
        BufferedInputStream(source, 64).use { stream ->
            assertEquals(0, stream.read())
            assertEquals(1, stream.read())
            assertEquals(2, stream.read())
            assertEquals(1, calls)
        }
    }

    @Test fun skipUsesPositionWithoutDownloadingAndAvailableIsConservative() {
        val positions = mutableListOf<Long>()
        val stream = SmbReadInputStream(fake(read = { position, _, _, _ ->
            positions += position
            -1
        }))
        assertEquals(0L, stream.skip(-1))
        assertEquals(11L, stream.skip(11))
        assertEquals(0, stream.available())
        assertEquals(-1, stream.read(ByteArray(2), 0, 2))
        assertEquals(listOf(11L), positions)
        stream.close()
    }

    @Test fun boundsAndInvalidNativeResponsesAreNotHiddenAsEof() {
        val stream = SmbReadInputStream(fake(read = { _, _, _, _ -> 0 }))
        assertThrows(IndexOutOfBoundsException::class.java) {
            stream.read(ByteArray(1), 1, 1)
        }
        assertThrows(IOException::class.java) {
            stream.read(ByteArray(1), 0, 1)
        }
        stream.close()
    }

    @Test fun protocolErrorPropagatesAndCloseReleasesHandleExactlyOnce() {
        var closes = 0
        val stream = SmbReadInputStream(fake(
            read = { _, _, _, _ -> throw IOException("disconnect") },
            close = { closes++ },
        ))
        assertEquals("disconnect", assertThrows(IOException::class.java) {
            stream.read(ByteArray(1), 0, 1)
        }.message)
        stream.close()
        stream.close()
        assertEquals(1, closes)
        assertThrows(IOException::class.java) { stream.read() }
    }

    private fun fake(
        read: (Long, ByteArray, Int, Int) -> Int,
        close: () -> Unit = {},
    ) = object : SmbRandomAccessHandle {
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            read(position, buffer, offset, length)
        override fun close() = close()
    }
}

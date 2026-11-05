package app.local1st.files.ui.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import java.io.IOException
import org.junit.Test
import java.io.ByteArrayOutputStream

class CastSmbRangeCopyTest {
    private val bytes = "abcdefghij".toByteArray()

    @Test
    fun independentlyRequestedRangesUseAbsoluteOffsetsWithoutSeek() {
        val positions = mutableListOf<Long>()
        fun copy(start: Long, length: Long): ByteArray {
            val out = ByteArrayOutputStream()
            copyCastSmbRange(
                start = start,
                length = length,
                chunkSize = 3,
                isActive = { true },
                readAt = { position, buffer, count ->
                    positions.add(position)
                    val available = (bytes.size - position.toInt()).coerceAtLeast(0)
                    val n = minOf(count, available)
                    bytes.copyInto(buffer, endIndex = position.toInt() + n, startIndex = position.toInt())
                    if (n == 0) -1 else n
                },
                write = { buffer, count -> out.write(buffer, 0, count) },
            )
            return out.toByteArray()
        }

        assertArrayEquals("abcde".toByteArray(), copy(start = 0, length = 5))
        assertArrayEquals("fghij".toByteArray(), copy(start = 5, length = 5))
        assertEquals(listOf(0L, 3L, 5L, 8L), positions)
    }

    @Test
    fun supersededRangeStopsBeforeFurtherReads() {
        val out = ByteArrayOutputStream()
        var reads = 0
        copyCastSmbRange(
            start = 2L,
            length = 8L,
            chunkSize = 2,
            isActive = { reads < 1 },
            readAt = { position, buffer, count ->
                reads++
                bytes.copyInto(buffer, startIndex = position.toInt(), endIndex = position.toInt() + count)
                count
            },
            write = { buffer, count -> out.write(buffer, 0, count) },
        )

        assertEquals(1, reads)
        assertArrayEquals("".toByteArray(), out.toByteArray())
    }

    @Test
    fun shortSourceReportsPrematureEof() {
        val out = ByteArrayOutputStream()
        var reads = 0
        assertThrows(IOException::class.java) {
            copyCastSmbRange(
            start = 9L,
            length = 5L,
            chunkSize = 3,
            isActive = { true },
            readAt = { position, buffer, count ->
                reads++
                val available = (bytes.size - position.toInt()).coerceAtLeast(0)
                val n = minOf(count, available)
                if (n > 0) bytes.copyInto(buffer, 0, position.toInt(), position.toInt() + n)
                if (n == 0) -1 else n
            },
            write = { buffer, count -> out.write(buffer, 0, count) },
            )
        }

        assertEquals(2, reads)
        assertArrayEquals("j".toByteArray(), out.toByteArray())
    }
}

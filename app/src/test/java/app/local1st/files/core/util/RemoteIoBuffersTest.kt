package app.local1st.files.core.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteIoBuffersTest {
    @Test
    fun sequentialReadStartsReadAheadAfterFirstContiguousRequest() {
        val source = ByteArray(64) { it.toByte() }
        val requests = mutableListOf<Pair<Long, Int>>()
        val readAhead = SequentialReadAhead(capacity = 16)
        val destination = ByteArray(8)
        val reader = reader(source, requests)

        assertEquals(4, readAhead.read(0, destination, 0, 4, reader))
        assertEquals(4, readAhead.read(4, destination, 0, 4, reader))
        assertEquals(4, readAhead.read(8, destination, 0, 4, reader))

        assertEquals(listOf(0L to 4, 4L to 16), requests)
        assertArrayEquals(byteArrayOf(8, 9, 10, 11), destination.copyOfRange(0, 4))
    }

    @Test
    fun randomSeekDoesNotDragReadAheadAcrossTheFile() {
        val source = ByteArray(64) { it.toByte() }
        val requests = mutableListOf<Pair<Long, Int>>()
        val readAhead = SequentialReadAhead(capacity = 16)
        val destination = ByteArray(4)
        val reader = reader(source, requests)

        readAhead.read(0, destination, 0, 4, reader)
        readAhead.read(4, destination, 0, 4, reader)
        readAhead.read(40, destination, 0, 4, reader)

        assertEquals(listOf(0L to 4, 4L to 16, 40L to 4), requests)
        assertArrayEquals(byteArrayOf(40, 41, 42, 43), destination)
    }

    @Test
    fun cacheHitCrossingReadAheadEndReturnsTheCompleteRequestedRange() {
        val source = ByteArray(64) { it.toByte() }
        val requests = mutableListOf<Pair<Long, Int>>()
        val readAhead = SequentialReadAhead(capacity = 16)
        val warmup = ByteArray(4)
        val destination = ByteArray(6)
        val reader = reader(source, requests)

        readAhead.read(0, warmup, 0, warmup.size, reader)
        readAhead.read(4, warmup, 0, warmup.size, reader)

        assertEquals(6, readAhead.read(17, destination, 0, destination.size, reader))
        assertArrayEquals(byteArrayOf(17, 18, 19, 20, 21, 22), destination)
        assertEquals(listOf(0L to 4, 4L to 16, 20L to 3), requests)
    }

    @Test
    fun cacheBoundaryReadIsShortOnlyAtTheRealFileEnd() {
        val source = ByteArray(21) { it.toByte() }
        val requests = mutableListOf<Pair<Long, Int>>()
        val readAhead = SequentialReadAhead(capacity = 16)
        val warmup = ByteArray(4)
        val destination = ByteArray(6)
        val reader = reader(source, requests)

        readAhead.read(0, warmup, 0, warmup.size, reader)
        readAhead.read(4, warmup, 0, warmup.size, reader)

        assertEquals(3, readAhead.read(18, destination, 0, destination.size, reader))
        assertArrayEquals(byteArrayOf(18, 19, 20), destination.copyOfRange(0, 3))
        assertEquals(listOf(0L to 4, 4L to 16, 20L to 4, 21L to 3), requests)
    }

    @Test
    fun cacheBoundaryReadKeepsLongOffsetsBeyondFourGiB() {
        val base = 5L * 1024L * 1024L * 1024L
        val requests = mutableListOf<Pair<Long, Int>>()
        val readAhead = SequentialReadAhead(capacity = 16)
        val warmup = ByteArray(4)
        val destination = ByteArray(6)
        val reader: (Long, ByteArray, Int, Int) -> Int = { position, target, offset, length ->
            requests += position to length
            repeat(length) { index ->
                target[offset + index] = ((position + index - base) and 0xffL).toByte()
            }
            length
        }

        readAhead.read(base, warmup, 0, warmup.size, reader)
        readAhead.read(base + 4, warmup, 0, warmup.size, reader)

        assertEquals(6, readAhead.read(base + 17, destination, 0, destination.size, reader))
        assertArrayEquals(byteArrayOf(17, 18, 19, 20, 21, 22), destination)
        assertEquals(listOf(base to 4, base + 4 to 16, base + 20 to 3), requests)
    }

    @Test
    fun sequentialWritesAreCombinedUntilFlush() {
        val writes = mutableListOf<WriteCall>()
        val buffer = SequentialWriteBuffer(capacity = 8)
        val sink = writer(writes)

        assertEquals(3, buffer.write(0, byteArrayOf(1, 2, 3), 0, 3, sink))
        assertEquals(3, buffer.write(3, byteArrayOf(4, 5, 6), 0, 3, sink))
        assertEquals(0, writes.size)

        buffer.flush(sink)

        assertEquals(1, writes.size)
        assertEquals(0L, writes.single().position)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), writes.single().data)
    }

    @Test
    fun nonContiguousWriteFlushesBeforeStartingNewRange() {
        val writes = mutableListOf<WriteCall>()
        val buffer = SequentialWriteBuffer(capacity = 8)
        val sink = writer(writes)

        buffer.write(0, byteArrayOf(1, 2, 3), 0, 3, sink)
        buffer.write(100, byteArrayOf(9, 8), 0, 2, sink)

        assertEquals(1, writes.size)
        assertEquals(0L, writes[0].position)
        assertArrayEquals(byteArrayOf(1, 2, 3), writes[0].data)

        buffer.flush(sink)
        assertEquals(100L, writes[1].position)
        assertArrayEquals(byteArrayOf(9, 8), writes[1].data)
    }

    private fun reader(
        source: ByteArray,
        requests: MutableList<Pair<Long, Int>>,
    ): (Long, ByteArray, Int, Int) -> Int = { position, destination, offset, length ->
        requests += position to length
        if (position >= source.size) {
            0
        } else {
            val count = minOf(length, source.size - position.toInt())
            source.copyInto(destination, offset, position.toInt(), position.toInt() + count)
            count
        }
    }

    private fun writer(
        writes: MutableList<WriteCall>,
    ): (Long, ByteArray, Int, Int) -> Int = { position, source, offset, length ->
        writes += WriteCall(position, source.copyOfRange(offset, offset + length))
        length
    }

    private data class WriteCall(val position: Long, val data: ByteArray)
}

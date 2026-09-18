package app.local1st.files.core.fs

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PipelinedAsyncOutputStreamTest {
    @Test
    fun writesStayInFlightUntilPipelineWindowFills() {
        val futures = mutableListOf<CountingFuture>()
        val stream = PipelinedAsyncOutputStream(chunkSize = 4, maxInFlight = 3) { _, _, length ->
            CountingFuture(length.toLong()).also(futures::add)
        }

        stream.write(ByteArray(8) { it.toByte() })

        assertEquals(2, futures.size)
        assertEquals(0, futures.sumOf { it.getCalls })

        stream.write(byteArrayOf(8, 9, 10, 11))

        assertEquals(3, futures.size)
        assertEquals(1, futures[0].getCalls)
        assertEquals(0, futures[1].getCalls)
        assertEquals(0, futures[2].getCalls)

        stream.close()
        assertEquals(1, futures[1].getCalls)
        assertEquals(1, futures[2].getCalls)
    }

    @Test
    fun flushSubmitsPartialChunkWithCorrectOffsetsAndWaitsForAllWrites() {
        val writes = mutableListOf<WriteCall>()
        val futures = mutableListOf<CountingFuture>()
        val stream = PipelinedAsyncOutputStream(chunkSize = 4, maxInFlight = 3) { buffer, position, length ->
            writes += WriteCall(position, buffer.copyOf(length))
            CountingFuture(length.toLong()).also(futures::add)
        }

        stream.write(ByteArray(10) { it.toByte() })
        stream.flush()

        assertEquals(listOf(0L, 4L, 8L), writes.map { it.position })
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), writes[0].data)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), writes[1].data)
        assertArrayEquals(byteArrayOf(8, 9), writes[2].data)
        assertEquals(listOf(1, 1, 1), futures.map { it.getCalls })
    }

    @Test
    fun shortAsyncWriteFailsInsteadOfSilentlyCorruptingTheCopy() {
        val stream = PipelinedAsyncOutputStream(chunkSize = 4, maxInFlight = 2) { _, _, length ->
            CompletableFuture.completedFuture(length.toLong() - 1L)
        }

        val error = assertThrows(IOException::class.java) {
            stream.write(byteArrayOf(1, 2, 3, 4))
            stream.flush()
        }

        assertEquals("Short SMB write: expected 4 bytes, wrote 3", error.message)
    }

    private data class WriteCall(val position: Long, val data: ByteArray)

    private class CountingFuture(
        private val result: Long,
    ) : Future<Long> {
        var getCalls: Int = 0
            private set

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true

        override fun get(): Long {
            getCalls++
            return result
        }

        override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): Long = get()
    }
}

package app.local1st.files.core.fs

import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/**
 * Buffers sequential output into fixed-size owned chunks and keeps a bounded number of async
 * writes in flight. Each submitted chunk owns its backing array until the corresponding future
 * completes, so callers may safely reuse their input buffers immediately after [write] returns.
 */
internal class PipelinedAsyncOutputStream(
    private val chunkSize: Int,
    private val maxInFlight: Int,
    private val submit: (buffer: ByteArray, position: Long, length: Int) -> Future<Long>,
) : OutputStream() {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(maxInFlight > 0) { "maxInFlight must be > 0" }
    }

    private data class PendingWrite(
        val future: Future<Long>,
        val expectedBytes: Int,
    )

    private val pending = ArrayDeque<PendingWrite>()
    private var buffer = ByteArray(chunkSize)
    private var bufferedBytes = 0
    private var position = 0L
    private var closed = false

    override fun write(value: Int) {
        ensureOpen()
        buffer[bufferedBytes++] = value.toByte()
        if (bufferedBytes == buffer.size) submitBuffered()
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        ensureOpen()
        if (offset < 0 || length < 0 || offset > source.size - length) {
            throw IndexOutOfBoundsException(
                "offset=$offset length=$length size=${source.size}",
            )
        }
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size - bufferedBytes)
            source.copyInto(
                destination = buffer,
                destinationOffset = bufferedBytes,
                startIndex = sourceOffset,
                endIndex = sourceOffset + count,
            )
            bufferedBytes += count
            sourceOffset += count
            remaining -= count
            if (bufferedBytes == buffer.size) submitBuffered()
        }
    }

    override fun flush() {
        ensureOpen()
        flushPending()
    }

    override fun close() {
        if (closed) return
        try {
            flushPending()
        } finally {
            closed = true
        }
    }

    private fun flushPending() {
        submitBuffered()
        while (pending.isNotEmpty()) awaitFirst()
    }

    private fun submitBuffered() {
        if (bufferedBytes == 0) return

        val length = bufferedBytes
        val payload = if (length == buffer.size) buffer else buffer.copyOf(length)
        val writePosition = position
        val future = try {
            submit(payload, writePosition, length)
        } catch (error: Throwable) {
            throw error.asIOException("Failed to submit SMB write")
        }

        position += length
        buffer = ByteArray(chunkSize)
        bufferedBytes = 0
        pending.addLast(PendingWrite(future, length))

        // Back-pressure keeps memory and SMB credits bounded while still allowing multiple WRITE
        // requests to overlap their request/response latency.
        if (pending.size >= maxInFlight) awaitFirst()
    }

    private fun awaitFirst() {
        val write = pending.removeFirst()
        val actualBytes = try {
            write.future.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for SMB write", error)
        } catch (error: ExecutionException) {
            throw (error.cause ?: error).asIOException("SMB write failed")
        } catch (error: CancellationException) {
            throw IOException("SMB write was cancelled", error)
        }

        if (actualBytes != write.expectedBytes.toLong()) {
            throw IOException(
                "Short SMB write: expected ${write.expectedBytes} bytes, wrote $actualBytes",
            )
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Stream is closed")
    }

    private fun Throwable.asIOException(fallbackMessage: String): IOException =
        if (this is IOException) this else IOException(message ?: fallbackMessage, this)
}

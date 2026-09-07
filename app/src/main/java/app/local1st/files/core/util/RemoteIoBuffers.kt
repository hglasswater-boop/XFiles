package app.local1st.files.core.util

import java.io.IOException
import kotlin.math.min

/**
 * Small bounded helpers used by [RemoteFileProvider] to reduce Binder/FUSE callback churn and
 * increase the size of sequential SMB operations without changing random-access semantics.
 */
internal class SequentialReadAhead(
    private val capacity: Int,
) {
    private var cache: ByteArray? = null
    private var cacheStart = -1L
    private var cacheLength = 0
    private var lastReadEnd = -1L

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
        sourceRead: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    ): Int {
        require(position >= 0L) { "position must be non-negative" }
        require(destinationOffset >= 0 && length >= 0 && destinationOffset + length <= destination.size) {
            "invalid destination range"
        }
        if (length == 0) return 0

        val localCache = cache
        if (
            localCache != null &&
            cacheLength > 0 &&
            position >= cacheStart &&
            position < cacheStart + cacheLength
        ) {
            val cacheOffset = (position - cacheStart).toInt()
            val copied = min(length, cacheLength - cacheOffset)
            localCache.copyInto(
                destination = destination,
                destinationOffset = destinationOffset,
                startIndex = cacheOffset,
                endIndex = cacheOffset + copied,
            )
            if (copied == length) {
                lastReadEnd = position + copied
                return copied
            }

            // ProxyFileDescriptorCallback.onRead() must return the complete requested range unless
            // the real file end is reached. A cache hit near the end of the read-ahead window must
            // therefore continue from SMB instead of exposing the cache boundary as a short read.
            invalidateCache()
            val remainder = readFully(
                position = position + copied,
                destination = destination,
                destinationOffset = destinationOffset + copied,
                length = length - copied,
                sourceRead = sourceRead,
            )
            val total = copied + remainder
            lastReadEnd = position + total
            return total
        }

        val sequential = position == lastReadEnd
        if (!sequential) {
            invalidateCache()
            val read = readFully(position, destination, destinationOffset, length, sourceRead)
            if (read > 0) lastReadEnd = position + read
            return read
        }

        val buffer = cache ?: ByteArray(capacity).also { cache = it }
        cacheStart = position
        cacheLength = readFully(
            position = position,
            destination = buffer,
            destinationOffset = 0,
            length = buffer.size,
            sourceRead = sourceRead,
        )
        if (cacheLength <= 0) return cacheLength

        val copied = min(length, cacheLength)
        buffer.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = 0,
            endIndex = copied,
        )
        lastReadEnd = position + copied
        return copied
    }

    private fun readFully(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
        sourceRead: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    ): Int {
        var total = 0
        while (total < length) {
            val read = sourceRead(
                position + total,
                destination,
                destinationOffset + total,
                length - total,
            )
            if (read <= 0) break
            total += read
        }
        return total
    }

    private fun invalidateCache() {
        cacheStart = -1L
        cacheLength = 0
    }
}

internal class SequentialWriteBuffer(
    private val capacity: Int,
) {
    private val buffer = ByteArray(capacity)
    private var start = -1L
    private var length = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun write(
        position: Long,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
        sinkWrite: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    ): Int {
        require(position >= 0L) { "position must be non-negative" }
        require(sourceOffset >= 0 && count >= 0 && sourceOffset + count <= source.size) {
            "invalid source range"
        }
        if (count == 0) return 0

        val contiguous = length == 0 || position == start + length
        if (!contiguous || length + count > capacity) flush(sinkWrite)

        if (count >= capacity) {
            writeFully(position, source, sourceOffset, count, sinkWrite)
            return count
        }

        if (length == 0) start = position
        source.copyInto(
            destination = buffer,
            destinationOffset = length,
            startIndex = sourceOffset,
            endIndex = sourceOffset + count,
        )
        length += count
        if (length == capacity) flush(sinkWrite)
        return count
    }

    fun flush(
        sinkWrite: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    ) {
        if (length == 0) return
        writeFully(start, buffer, 0, length, sinkWrite)
        start = -1L
        length = 0
    }

    private fun writeFully(
        position: Long,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
        sinkWrite: (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int,
    ) {
        var written = 0
        while (written < count) {
            val step = sinkWrite(
                position + written,
                source,
                sourceOffset + written,
                count - written,
            )
            if (step <= 0) throw IOException("SMB write made no progress")
            written += step
        }
    }
}

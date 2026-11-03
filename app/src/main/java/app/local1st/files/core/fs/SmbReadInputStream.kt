package app.local1st.files.core.fs

import java.io.IOException
import java.io.InputStream

/**
 * Sequential InputStream view of a positional SMB read handle.
 *
 * Keep each native read bounded and the stream's logical position independent from network state.
 * The caller owns cancellation: closing the stream always closes its underlying SMB handle.
 */
internal class SmbReadInputStream(
    private val handle: SmbRandomAccessHandle,
) : InputStream() {
    private var position = 0L
    private var closed = false
    private val oneByte = ByteArray(1)

    @Synchronized
    override fun read(): Int =
        if (read(oneByte, 0, 1) == -1) -1 else oneByte[0].toInt() and 0xff

    @Synchronized
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("Invalid SMB input buffer range")
        }
        requireOpen()
        if (length == 0) return 0
        if (position == Long.MAX_VALUE) return -1

        val request = minOf(length.toLong(), MAX_READ_BYTES.toLong(), Long.MAX_VALUE - position).toInt()
        val count = handle.read(position, buffer, offset, request)
        if (count == -1) return -1
        if (count <= 0 || count > request) {
            throw IOException("Invalid SMB read count: $count / $request")
        }
        position += count
        return count
    }

    @Synchronized
    override fun skip(n: Long): Long {
        requireOpen()
        if (n <= 0) return 0
        val skipped = minOf(n, Long.MAX_VALUE - position)
        position += skipped
        return skipped
    }

    @Synchronized
    override fun available(): Int {
        requireOpen()
        // No extra network QUERY_INFO call. Zero is a legal conservative lower bound.
        return 0
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }

    private fun requireOpen() {
        if (closed) throw IOException("SMB input stream is closed")
    }

    private companion object {
        const val MAX_READ_BYTES = 1024 * 1024
    }
}

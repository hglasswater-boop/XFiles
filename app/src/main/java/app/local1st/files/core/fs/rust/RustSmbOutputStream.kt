package app.local1st.files.core.fs.rust

import java.io.IOException
import java.io.OutputStream

/**
 * Synchronous, bounded Rust SMB writer for diagnostic use only.
 *
 * No retry after an ambiguous WRITE/FLUSH failure. Close always releases the native handle.
 */
internal class RustSmbOutputStream(
    private val handle: Long,
    private val writeNative: (Long, Long, ByteArray, Int, Int) -> Int,
    private val flushNative: (Long) -> Unit,
    private val closeNative: (Long) -> Unit,
) : OutputStream() {
    private var position = 0L
    private var closed = false
    private var failed = false

    @Synchronized
    override fun write(value: Int) {
        write(byteArrayOf(value.toByte()), 0, 1)
    }

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) {
            throw IndexOutOfBoundsException("invalid SMB stream source range")
        }
        requireWritable()
        if (length == 0) return
        if (position > Long.MAX_VALUE - length.toLong()) {
            throw IOException("SMB output position exceeds Long range")
        }

        var cursor = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = remaining.coerceAtMost(MAX_JNI_CHUNK_SIZE)
            val accepted = try {
                writeNative(handle, position, bytes, cursor, chunk)
            } catch (error: Throwable) {
                failed = true
                throw error
            }
            if (accepted != chunk) {
                failed = true
                throw IOException("SMB native WRITE was incomplete: $accepted / $chunk")
            }
            position += accepted
            cursor += chunk
            remaining -= chunk
        }
    }

    @Synchronized
    override fun flush() {
        requireWritable()
        try {
            flushNative(handle)
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true

        var error: Throwable? = null
        if (!failed) {
            try {
                flushNative(handle)
            } catch (flushError: Throwable) {
                failed = true
                error = flushError
            }
        }
        try {
            closeNative(handle)
        } catch (closeError: Throwable) {
            val first = error
            if (first == null) {
                error = closeError
            } else {
                first.addSuppressed(closeError)
            }
        }
        error?.let { throw it }
    }

    private fun requireWritable() {
        if (closed) throw IOException("Rust SMB output stream is closed")
        if (failed) throw IOException("Rust SMB output stream cannot continue after a failed write")
    }

    companion object {
        private const val MAX_JNI_CHUNK_SIZE = 1024 * 1024
    }
}

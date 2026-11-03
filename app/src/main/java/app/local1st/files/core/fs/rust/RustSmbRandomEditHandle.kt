package app.local1st.files.core.fs.rust

import app.local1st.files.core.fs.SmbRandomEditHandle
import java.io.IOException

/**
 * Diagnostic-only FILE_OPEN editor. Operations use the same native SMB handle, not openOut's
 * truncating OVERWRITE_IF. Neither short writes nor ambiguous failures are replayed.
 */
internal class RustSmbRandomEditHandle(
    private val handle: Long,
    private val readNative: (Long, Long, ByteArray, Int, Int) -> Int,
    private val writeNative: (Long, Long, ByteArray, Int, Int) -> Int,
    private val setLengthNative: (Long, Long) -> Unit,
    private val flushNative: (Long) -> Unit,
    private val closeNative: (Long) -> Unit,
) : SmbRandomEditHandle {
    private var closed = false
    private var failed = false

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        validate(position, buffer, offset, length)
        requireUsable()
        if (length == 0) return 0

        var total = 0
        while (total < length) {
            val requested = (length - total).coerceAtMost(MAX_JNI_BYTES)
            val count = try {
                readNative(handle, position + total, buffer, offset + total, requested)
            } catch (error: Throwable) {
                failed = true
                throw error
            }
            if (count == -1) return if (total == 0) -1 else total
            if (count <= 0 || count > requested) {
                failed = true
                throw IOException("Invalid Rust SMB READ result: $count / $requested")
            }
            total += count
            if (count < requested) break
        }
        return total
    }

    @Synchronized
    override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        validate(position, buffer, offset, length)
        requireUsable()
        if (length == 0) return 0
        if (position > Long.MAX_VALUE - length.toLong()) {
            throw IllegalArgumentException("SMB output position exceeds signed length")
        }

        var total = 0
        while (total < length) {
            val requested = (length - total).coerceAtMost(MAX_JNI_BYTES)
            val count = try {
                writeNative(handle, position + total, buffer, offset + total, requested)
            } catch (error: Throwable) {
                failed = true
                throw error
            }
            if (count != requested) {
                failed = true
                throw IOException("Incomplete Rust SMB WRITE: $count / $requested")
            }
            total += count
        }
        return total
    }

    @Synchronized
    override fun setLength(length: Long) {
        require(length >= 0L) { "length must be >= 0" }
        requireUsable()
        try {
            setLengthNative(handle, length)
        } catch (error: Throwable) {
            failed = true
            throw error
        }
    }

    @Synchronized
    override fun flush() {
        requireUsable()
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
        // A failed WRITE or SET_INFO is never followed by a speculative FLUSH or another mutation.
        closeNative(handle)
    }

    private fun requireUsable() {
        if (closed) throw IOException("Rust SMB editor is closed")
        if (failed) throw IOException("Rust SMB editor cannot continue after a failed operation")
    }

    private fun validate(position: Long, data: ByteArray, offset: Int, length: Int) {
        require(position >= 0) { "position must be >= 0" }
        if (offset < 0 || length < 0 || offset > data.size || length > data.size - offset) {
            throw IndexOutOfBoundsException("invalid SMB random editor buffer range")
        }
        if (length > 0 && position > Long.MAX_VALUE - length.toLong()) {
            throw IllegalArgumentException("SMB random editor range exceeds signed offset")
        }
    }

    private companion object {
        const val MAX_JNI_BYTES = 1024 * 1024
    }
}

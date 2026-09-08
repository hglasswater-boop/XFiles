package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend-neutral seekable read handle for one SMB file.
 *
 * Callers keep using this facade while the implementation can move from SMBJ to the Pure Rust SMB
 * engine behind [SmbRandomAccessBackends]. Positional I/O remains the public primitive.
 */
class SmbRandomAccessFile private constructor(
    private val handle: SmbRandomAccessHandle,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed.get()) { "SMB file is closed" }
        if (position < 0L || length <= 0) return -1
        return handle.read(position, buffer, offset, length)
    }

    /**
     * Signals a material access-position discontinuity without serializing behind an in-flight read.
     * SMBJ treats this as a no-op; the Rust backend advances its generation and can send SMB2 CANCEL
     * while the old read is still waiting on the network.
     */
    fun seek(position: Long) {
        check(!closed.get()) { "SMB file is closed" }
        if (position >= 0L) handle.seek(position)
    }

    /** Optional speculative read hint; backends may ignore or preempt it. */
    fun prefetch(position: Long, length: Int) {
        check(!closed.get()) { "SMB file is closed" }
        if (position >= 0L && length > 0) handle.prefetch(position, length)
    }

    /** True when the selected backend already owns read-ahead/cache below the Kotlin boundary. */
    val ownsReadAhead: Boolean
        get() {
            check(!closed.get()) { "SMB file is closed" }
            return handle.ownsReadAhead
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) handle.close()
    }

    companion object {
        fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessFile =
            SmbRandomAccessFile(SmbRandomAccessBackends.open(id, connections))
    }
}

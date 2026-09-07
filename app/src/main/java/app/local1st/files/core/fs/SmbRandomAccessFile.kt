package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.Closeable

/**
 * Backend-neutral seekable read handle for one SMB file.
 *
 * Callers keep using this facade while the implementation can move from SMBJ to the Pure Rust SMB
 * engine behind [SmbRandomAccessBackends]. Positional I/O remains the public primitive.
 */
class SmbRandomAccessFile private constructor(
    private val handle: SmbRandomAccessHandle,
) : Closeable {
    private var closed = false

    @Synchronized
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "SMB file is closed" }
        if (position < 0L || length <= 0) return -1
        return handle.read(position, buffer, offset, length)
    }

    /**
     * Signals a material access-position discontinuity without changing the positional read API.
     * SMBJ treats this as a no-op; the Rust backend uses it to cancel stale read-ahead work.
     */
    @Synchronized
    fun seek(position: Long) {
        check(!closed) { "SMB file is closed" }
        if (position >= 0L) handle.seek(position)
    }

    /** True when the selected backend already owns read-ahead/cache below the Kotlin boundary. */
    val ownsReadAhead: Boolean
        @Synchronized get() {
            check(!closed) { "SMB file is closed" }
            return handle.ownsReadAhead
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        handle.close()
    }

    companion object {
        fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessFile =
            SmbRandomAccessFile(SmbRandomAccessBackends.open(id, connections))
    }
}

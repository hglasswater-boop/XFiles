package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.Closeable

/**
 * Backend boundary for seekable SMB reads.
 *
 * The public XFiles facade remains [SmbRandomAccessFile]. Implementations may be SMBJ or the
 * native Rust engine, while callers such as Media3 and thumbnail extraction stay backend-agnostic.
 */
internal interface SmbRandomAccessHandle : Closeable {
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Signals a material position discontinuity.
     *
     * SMBJ does not need this. The Rust implementation uses it to advance the stream generation so
     * stale in-flight READ requests can be cancelled before the next positional read is dispatched.
     */
    fun seek(position: Long) = Unit

    /** True when this handle already owns read-ahead/cache policy below the Kotlin boundary. */
    val ownsReadAhead: Boolean
        get() = false
}

internal fun interface SmbRandomAccessBackend {
    fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle
}

/**
 * Single migration seam for seekable SMB I/O.
 *
 * Keep the default on SMBJ until the Rust native library is packaged and its compatibility gates
 * pass. The selection point lives here rather than leaking a backend feature flag into viewers.
 */
internal object SmbRandomAccessBackends {
    @Volatile
    private var backend: SmbRandomAccessBackend = SmbjRandomAccessBackend

    fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle =
        backend.open(id, connections)

    internal fun installForTesting(value: SmbRandomAccessBackend): AutoCloseable {
        val previous = backend
        backend = value
        return AutoCloseable { backend = previous }
    }
}

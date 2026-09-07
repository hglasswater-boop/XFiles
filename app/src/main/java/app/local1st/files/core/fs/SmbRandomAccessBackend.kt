package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.prefs.SmbConnectionConfig
import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.Closeable
import java.io.IOException

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

internal data class SmbRandomAccessTarget(
    val connection: SmbConnectionConfig,
    /** Path inside the configured share, including its optional base path. */
    val path: String,
)

internal fun resolveSmbRandomAccessTarget(
    id: String,
    connections: SmbConnectionRepo,
): SmbRandomAccessTarget {
    require(id.startsWith("${XId.SCHEME_SMB}://") && id != SmbFileSystem.ROOT_ID) {
        "Invalid SMB id: $id"
    }
    val raw = id.removePrefix("${XId.SCHEME_SMB}://").trimEnd('/')
    val connectionId = raw.substringBefore('/')
    val connection = connections.find(connectionId)
        ?: throw IOException("SMB connection is no longer configured")
    val relativePath = raw.substringAfter('/', "")
    require(relativePath.isNotBlank()) { "SMB connection root is not a file" }
    val path = when {
        connection.basePath.isBlank() -> relativePath
        else -> "${connection.basePath}/$relativePath"
    }
    return SmbRandomAccessTarget(connection, path)
}

/**
 * Single migration seam for seekable SMB I/O.
 *
 * The default build stays on SMBJ. Developer/benchmark builds can opt into Rust with
 * `-PxfilesSmbBackend=rust`; no viewer or business code sees that flag.
 */
internal object SmbRandomAccessBackends {
    @Volatile
    private var backend: SmbRandomAccessBackend = when (BuildConfig.SMB_RANDOM_ACCESS_BACKEND) {
        "rust" -> RustSmbRandomAccessBackend
        else -> SmbjRandomAccessBackend
    }

    fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle =
        backend.open(id, connections)

    internal fun installForTesting(value: SmbRandomAccessBackend): AutoCloseable {
        val previous = backend
        backend = value
        return AutoCloseable { backend = previous }
    }
}

package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbUnavailableException
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

    /**
     * Optional speculative read hint.
     *
     * Backends that do not own a prefetch scheduler ignore it. Rust may cancel this work at any
     * time when foreground READ, seek, or close needs the SMB connection.
     */
    fun prefetch(position: Long, length: Int) = Unit

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
 * Production migration policy: prefer the native Rust engine, but keep SMBJ as a narrow safety net
 * only when the Rust JNI library itself is unavailable or incompatible. Connection/protocol errors
 * from Rust are intentionally not swallowed, so real Rust regressions remain visible.
 */
internal object AutoSmbRandomAccessBackend : SmbRandomAccessBackend {
    override fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle =
        try {
            RustSmbRandomAccessBackend.open(id, connections)
        } catch (_: RustSmbUnavailableException) {
            SmbjRandomAccessBackend.open(id, connections)
        }
}

/**
 * Single migration seam for seekable SMB I/O.
 *
 * Normal builds keep Rust as the preferred random-access engine for thumbnails/storyboards, while
 * local Media3 playback stays on the established SMBJ path until the Rust playback stream has been
 * proven stable under long continuous playback. Strict `rust` builds still exercise Rust for both
 * paths, and explicit `smbj` builds keep SMBJ everywhere.
 */
internal object SmbRandomAccessBackends {
    @Volatile
    private var backend: SmbRandomAccessBackend = when (BuildConfig.SMB_RANDOM_ACCESS_BACKEND) {
        "rust" -> RustSmbRandomAccessBackend
        "smbj" -> SmbjRandomAccessBackend
        else -> AutoSmbRandomAccessBackend
    }

    @Volatile
    private var playbackBackend: SmbRandomAccessBackend = when (BuildConfig.SMB_RANDOM_ACCESS_BACKEND) {
        "rust" -> RustSmbRandomAccessBackend
        "smbj" -> SmbjRandomAccessBackend
        else -> SmbjRandomAccessBackend
    }

    fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle =
        backend.open(id, connections)

    fun openForPlayback(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle =
        playbackBackend.open(id, connections)

    internal fun installForTesting(value: SmbRandomAccessBackend): AutoCloseable {
        val previous = backend
        val previousPlayback = playbackBackend
        backend = value
        playbackBackend = value
        return AutoCloseable {
            backend = previous
            playbackBackend = previousPlayback
        }
    }
}

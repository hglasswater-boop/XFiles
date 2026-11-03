package app.local1st.files.core.fs.rust

import java.io.IOException

/** Raised only when the native Rust engine cannot be used on the current build/device. */
internal class RustSmbUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Mechanical JNI binding for the Pure Rust SMB engine. */
internal object RustSmbNative {
    private const val EXPECTED_API_VERSION = 6

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("smb_io_android")
        } catch (error: UnsatisfiedLinkError) {
            throw RustSmbUnavailableException(
                "Rust SMB native library is not packaged for this device",
                error,
            )
        }
        val actual = nativeApiVersion()
        if (actual != EXPECTED_API_VERSION) {
            throw RustSmbUnavailableException(
                "Rust SMB native API mismatch: expected $EXPECTED_API_VERSION, found $actual",
            )
        }
        loaded = true
    }

    external fun nativeApiVersion(): Int

    external fun nativeOpenVideo(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    ): Long

    /** One-shot mkdir. Never retry a failed/ambiguous mutation through SMBJ. */
    external fun nativeMkdir(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    )

    /** One same-share rename (no overwrite). An ambiguous network failure is not replayed. */
    external fun nativeRename(
        host: String,
        port: Int,
        share: String,
        source: String,
        destination: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    )

    /** One regular-file deletion only; XFiles does not route its delete operation here yet. */
    external fun nativeDeleteFile(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    )

    /** Opens a share-relative directory cursor (empty path means the share root). */
    external fun nativeOpenDirectory(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    ): Long

    /** Exactly one QUERY_DIRECTORY response, encoded as an XFL1 page; zero-count means EOF. */
    external fun nativeReadDirectoryPage(handle: Long): ByteArray

    /** Always close the native directory cursor after reading, including on failure. */
    external fun nativeCloseDirectory(handle: Long)

    /** Create exactly one new empty file. Never overwrite or retry an ambiguous CREATE. */
    external fun nativeCreateFile(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    )

    /** Explicit diagnostic OVERWRITE_IF output; do not use in production auto until speed gate. */
    external fun nativeOpenWritable(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        workstation: String,
    ): Long

    /** Bounded positional WRITE on the same native file handle, maximum 1MiB per call. */
    external fun nativeWriteAt(
        handle: Long,
        position: Long,
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
    ): Int

    /** Signed SMB FLUSH; caller must still close the native handle in finally. */
    external fun nativeFlushWritable(handle: Long)

    /** Releases the writable handle even after an earlier WRITE/FLUSH error. */
    external fun nativeCloseWritable(handle: Long)

    external fun nativeLen(handle: Long): Long

    external fun nativeReadAt(
        handle: Long,
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int

    /** Optional speculative read hint. Foreground reads, seek, and close may preempt it. */
    external fun nativePrefetch(handle: Long, position: Long, length: Int)

    external fun nativeSeek(handle: Long, position: Long): Long

    external fun nativeClose(handle: Long)
}

package app.local1st.files.core.fs.rust

import java.io.IOException

/** Raised only when the native Rust engine cannot be used on the current build/device. */
internal class RustSmbUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Mechanical JNI binding for the Pure Rust SMB engine. */
internal object RustSmbNative {
    private const val EXPECTED_API_VERSION = 4

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

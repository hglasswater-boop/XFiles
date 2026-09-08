package app.local1st.files.core.fs.rust

import java.io.IOException

/** Mechanical JNI binding for the Pure Rust SMB engine. */
internal object RustSmbNative {
    private const val EXPECTED_API_VERSION = 2

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("smb_io_android")
        } catch (error: UnsatisfiedLinkError) {
            throw IOException("Rust SMB native library is not packaged for this device", error)
        }
        val actual = nativeApiVersion()
        if (actual != EXPECTED_API_VERSION) {
            throw IOException(
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

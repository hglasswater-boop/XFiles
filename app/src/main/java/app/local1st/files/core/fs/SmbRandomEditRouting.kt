package app.local1st.files.core.fs

import java.io.Closeable

/** Backend-neutral existing-file editor; unlike openOut, opening never truncates. */
internal interface SmbRandomEditHandle : Closeable {
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    fun write(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
    fun setLength(length: Long)
    fun flush()
}

/** Keep the known SMBJ random editor as the production auto backend. */
internal object SmbRandomEditRouting {
    fun <T> route(
        mode: String,
        ensureNative: () -> Unit,
        cleanup: () -> Unit,
        rust: () -> T,
        smbj: () -> T,
    ): T {
        if (mode != "rust") return smbj()
        ensureNative()
        cleanup()
        return rust()
    }
}

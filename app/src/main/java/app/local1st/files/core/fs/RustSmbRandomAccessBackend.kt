package app.local1st.files.core.fs

import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.prefs.SmbConnectionRepo
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Seekable SMB reads backed by the native Pure Rust engine. */
internal object RustSmbRandomAccessBackend : SmbRandomAccessBackend {
    override fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessHandle {
        val target = resolveSmbRandomAccessTarget(id, connections)
        val config = target.connection
        if (config.username.isBlank()) {
            throw IOException("Rust SMB backend does not support anonymous sessions yet")
        }

        RustSmbNative.ensureLoaded()
        val nativeHandle = RustSmbNative.nativeOpenVideo(
            host = config.host,
            port = config.port,
            share = config.share,
            path = target.path,
            username = config.username,
            password = connections.password(config.id),
            domain = config.domain,
            workstation = "",
        )
        if (nativeHandle <= 0L) {
            throw IOException("Rust SMB backend returned an invalid video handle")
        }
        return Handle(nativeHandle)
    }

    private class Handle(
        private val nativeHandle: Long,
    ) : SmbRandomAccessHandle {
        private val closed = AtomicBoolean(false)

        override val ownsReadAhead: Boolean = true

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed.get()) { "SMB file is closed" }
            if (position < 0L || length <= 0) return -1
            val count = RustSmbNative.nativeReadAt(
                handle = nativeHandle,
                position = position,
                destination = buffer,
                destinationOffset = offset,
                length = length,
            )
            return if (count == 0) -1 else count
        }

        override fun seek(position: Long) {
            check(!closed.get()) { "SMB file is closed" }
            if (position >= 0L) RustSmbNative.nativeSeek(nativeHandle, position)
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                RustSmbNative.nativeClose(nativeHandle)
            }
        }
    }
}

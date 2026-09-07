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
        val anonymous = config.username.isBlank()

        RustSmbNative.ensureLoaded()
        val nativeHandle = RustSmbNative.nativeOpenVideo(
            host = config.host,
            port = config.port,
            share = config.share,
            path = target.path,
            username = if (anonymous) "" else config.username,
            // Match SMBJ semantics: a blank username means anonymous and any stored password is
            // ignored. Do not move an irrelevant secret across JNI in that case.
            password = if (anonymous) "" else connections.password(config.id),
            domain = if (anonymous) "" else config.domain,
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

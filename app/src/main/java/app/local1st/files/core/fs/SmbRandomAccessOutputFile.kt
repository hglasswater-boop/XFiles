package app.local1st.files.core.fs

import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.rust.RustSmbNative
import app.local1st.files.core.fs.rust.RustSmbRandomEditHandle
import app.local1st.files.core.prefs.SmbConnectionConfig
import app.local1st.files.core.prefs.SmbConnectionRepo
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet

/** Seekable read/write handle for one already-created SMB file. */
class SmbRandomAccessOutputFile private constructor(
    private val handle: SmbRandomEditHandle,
) : Closeable {
    private var closed = false

    @Synchronized
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "SMB file is closed" }
        if (position < 0L || length <= 0) return -1
        return handle.read(position, buffer, offset, length)
    }

    @Synchronized
    fun write(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "SMB file is closed" }
        require(position >= 0L) { "position must be >= 0" }
        if (length <= 0) return 0
        return handle.write(position, buffer, offset, length)
    }

    @Synchronized
    fun setLength(length: Long) {
        check(!closed) { "SMB file is closed" }
        require(length >= 0L) { "length must be >= 0" }
        handle.setLength(length)
    }

    @Synchronized
    fun flush() {
        check(!closed) { "SMB file is closed" }
        try {
            handle.flush()
        } catch (error: Throwable) {
            if (error is IOException) throw error
            throw IOException(error.message ?: "SMB output flush failed", error)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { handle.close() }
    }

    /** Preserve existing SMBJ operation semantics and explicit close ordering. */
    private class SmbjHandle(
        private val client: SMBClient,
        private val share: DiskShare,
        private val file: SmbFile,
    ) : SmbRandomEditHandle {
        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            file.read(buffer, position, offset, length)

        override fun write(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
            file.write(buffer, position, offset, length).toInt()

        override fun setLength(length: Long) = file.setLength(length)

        override fun flush() = file.flush()

        override fun close() {
            runCatching { file.close() }
            runCatching { share.close() }
            runCatching { client.close() }
        }
    }

    companion object {
        private const val CLIPFORGE_PARTIAL_PREFIX = ".clipforge-partial-"
        private const val STALE_PARTIAL_AGE_MS = 24L * 60L * 60L * 1000L

        private val SHARE_ACCESS: EnumSet<SMB2ShareAccess> = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2ShareAccess.FILE_SHARE_DELETE,
        )

        fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessOutputFile {
            val target = resolveTarget(id, connections)
            val selected = SmbRandomEditRouting.route(
                mode = BuildConfig.SMB_RANDOM_ACCESS_BACKEND,
                ensureNative = RustSmbNative::ensureLoaded,
                cleanup = { cleanupForRust(target, connections) },
                rust = { openRust(target, connections) },
                smbj = { openSmbj(target, connections) },
            )
            return SmbRandomAccessOutputFile(selected)
        }

        private fun openRust(target: Target, connections: SmbConnectionRepo): SmbRandomEditHandle {
            val config = target.connection
            val anonymous = config.username.isBlank()
            val handle = RustSmbNative.nativeOpenRandom(
                host = config.host,
                port = config.port,
                share = config.share,
                path = target.path,
                username = if (anonymous) "" else config.username,
                password = if (anonymous) "" else connections.password(config.id),
                domain = if (anonymous) "" else config.domain,
                workstation = "",
            )
            if (handle <= 0L) throw IOException("Rust SMB random editor returned invalid handle")
            return RustSmbRandomEditHandle(
                handle = handle,
                readNative = RustSmbNative::nativeReadRandomAt,
                writeNative = RustSmbNative::nativeWriteRandomAt,
                setLengthNative = RustSmbNative::nativeSetRandomLength,
                flushNative = RustSmbNative::nativeFlushRandom,
                closeNative = RustSmbNative::nativeCloseRandom,
            )
        }

        private fun openSmbj(target: Target, connections: SmbConnectionRepo): SmbRandomEditHandle {
            val client = SmbClientFactory.create()
            var share: DiskShare? = null
            try {
                share = connectSmbjShare(client, target, connections)
                cleanupStaleClipForgePartials(share, target.path)
                val file = share.openFile(
                    target.path.replace('/', '\\'),
                    EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                    EnumSet.noneOf(FileAttributes::class.java),
                    SHARE_ACCESS,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
                return SmbjHandle(client, share, file)
            } catch (error: Throwable) {
                runCatching { share?.close() }
                runCatching { client.close() }
                if (error is IOException) throw error
                throw IOException(error.message ?: "SMB random-access output open failed", error)
            }
        }

        /** Temporary SMBJ cleanup parity until standalone Rust handles stale ClipForge partials. */
        private fun cleanupForRust(target: Target, connections: SmbConnectionRepo) {
            val client = SmbClientFactory.create()
            var share: DiskShare? = null
            try {
                share = connectSmbjShare(client, target, connections)
                cleanupStaleClipForgePartials(share, target.path)
            } finally {
                runCatching { share?.close() }
                runCatching { client.close() }
            }
        }

        private fun connectSmbjShare(
            client: SMBClient,
            target: Target,
            connections: SmbConnectionRepo,
        ): DiskShare {
            val connection = client.connect(target.connection.host, target.connection.port)
            val auth = if (target.connection.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    target.connection.username,
                    connections.password(target.connection.id).toCharArray(),
                    target.connection.domain.takeIf { it.isNotBlank() },
                )
            }
            val session = connection.authenticate(auth)
            val connected = session.connectShare(target.connection.share)
            if (connected !is DiskShare) {
                runCatching { connected.close() }
                throw IOException("SMB share is not a disk share")
            }
            return connected
        }

        private fun cleanupStaleClipForgePartials(share: DiskShare, outputPath: String) {
            val parentPath = outputPath.substringBeforeLast('/', "")
            val cutoff = System.currentTimeMillis() - STALE_PARTIAL_AGE_MS
            runCatching {
                share.list(parentPath.replace('/', '\\'))
                    .asSequence()
                    .filter { info ->
                        info.fileName.startsWith(CLIPFORGE_PARTIAL_PREFIX) &&
                            info.lastWriteTime.toEpochMillis().let { mtime -> mtime > 0L && mtime < cutoff }
                    }
                    .forEach { info ->
                        val stalePath = if (parentPath.isBlank()) {
                            info.fileName
                        } else {
                            "$parentPath/${info.fileName}"
                        }
                        runCatching { share.rm(stalePath.replace('/', '\\')) }
                    }
            }
        }

        private data class Target(
            val connection: SmbConnectionConfig,
            val path: String,
        )

        private fun resolveTarget(id: String, connections: SmbConnectionRepo): Target {
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
            return Target(connection, path)
        }
    }
}

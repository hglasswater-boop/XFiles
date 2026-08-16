package app.local1st.files.core.fs

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

/**
 * Seekable read handle for one SMB file.
 *
 * SMBJ's [SmbFile.read] accepts a remote file offset, so media playback and thumbnail extraction
 * can jump straight to MP4 metadata/keyframes instead of reopening the stream and discarding
 * gigabytes with InputStream.skip().
 */
class SmbRandomAccessFile private constructor(
    private val client: SMBClient,
    private val share: DiskShare,
    private val file: SmbFile,
) : Closeable {
    private var closed = false

    @Synchronized
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "SMB file is closed" }
        if (position < 0L || length <= 0) return -1
        return file.read(buffer, position, offset, length)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { file.close() }
        runCatching { share.close() }
        runCatching { client.close() }
    }

    companion object {
        private val SHARE_ACCESS: EnumSet<SMB2ShareAccess> = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2ShareAccess.FILE_SHARE_DELETE,
        )

        fun open(id: String, connections: SmbConnectionRepo): SmbRandomAccessFile {
            val target = resolveTarget(id, connections)
            val client = SMBClient()
            try {
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
                    throw IOException("SMB share '${target.connection.share}' is not a disk share")
                }
                val file = connected.openFile(
                    target.path.replace('/', '\\'),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.noneOf(FileAttributes::class.java),
                    SHARE_ACCESS,
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
                return SmbRandomAccessFile(client, connected, file)
            } catch (error: Throwable) {
                runCatching { client.close() }
                if (error is IOException) throw error
                throw IOException(error.message ?: "SMB random-access open failed", error)
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

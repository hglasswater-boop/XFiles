package app.local1st.files.core.fs

import app.local1st.files.core.prefs.SmbConnectionConfig
import app.local1st.files.core.prefs.SmbConnectionRepo
import app.local1st.files.core.util.FileTypes
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/** SMB2/SMB3 filesystem backed by SMBJ. Each saved connection represents one share. */
open class SmbFileSystem(
    private val connections: SmbConnectionRepo,
) : XFileSystem {
    override val scheme: String = XId.SCHEME_SMB

    override fun list(dir: XEntry): List<XEntry> {
        if (dir.id == ROOT_ID) return connections.connections.value.map(::connectionEntry)
        val target = target(dir.id)
        return withShare(target.connection) { share ->
            share.list(toSmbPath(target.path))
                .asSequence()
                .filterNot { it.fileName == "." || it.fileName == ".." }
                .map { info ->
                    val isDir = EnumWithValue.EnumUtils.isSet(
                        info.fileAttributes,
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                    )
                    val hidden = EnumWithValue.EnumUtils.isSet(
                        info.fileAttributes,
                        FileAttributes.FILE_ATTRIBUTE_HIDDEN,
                    )
                    XEntry(
                        id = XId.child(dir, info.fileName),
                        name = info.fileName,
                        isDir = isDir,
                        size = if (isDir) -1L else info.endOfFile,
                        mtime = info.lastWriteTime.toEpochMillis(),
                        mime = if (isDir) null else FileTypes.mimeOf(info.fileName),
                        hidden = hidden || info.fileName.startsWith('.'),
                        canRead = true,
                        canWrite = true,
                        kind = if (isDir) EntryKind.DIR else EntryKind.FILE,
                    )
                }
                .toList()
        }
    }

    override fun stat(id: String): XEntry? {
        if (id == ROOT_ID) return rootEntry()
        val raw = id.removePrefix("$scheme://").trimEnd('/')
        if (!raw.contains('/')) {
            return connections.find(raw)?.let(::connectionEntry)
        }
        val parentId = XId.parent(id) ?: return null
        val parent = XEntry(
            id = parentId,
            name = parentId.substringAfterLast('/').ifBlank { "SMB" },
            isDir = true,
            kind = EntryKind.DIR,
        )
        return runCatching { list(parent).firstOrNull { it.id == id } }.getOrNull()
    }

    override fun openIn(entry: XEntry): InputStream {
        val target = target(entry.id)
        val handle = openFile(
            target = target,
            access = EnumSet.of(AccessMask.GENERIC_READ),
            disposition = SMB2CreateDisposition.FILE_OPEN,
        )
        val stream = try {
            handle.file.inputStream
        } catch (error: Throwable) {
            handle.close()
            throw error
        }
        return object : FilterInputStream(stream) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    handle.close()
                }
            }
        }
    }

    override fun openOut(parentDir: XEntry, name: String): OutputStream {
        requireSafeEntryName(name)
        val target = target(XId.child(parentDir, name))
        val handle = openFile(
            target = target,
            access = EnumSet.of(AccessMask.GENERIC_WRITE),
            disposition = SMB2CreateDisposition.FILE_OVERWRITE_IF,
        )
        val stream = try {
            handle.file.outputStream
        } catch (error: Throwable) {
            handle.close()
            throw error
        }
        return object : FilterOutputStream(stream) {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } finally {
                    handle.close()
                }
            }
        }
    }

    override fun createFile(parentDir: XEntry, name: String): XEntry {
        requireSafeEntryName(name)
        val childId = XId.child(parentDir, name)
        val target = target(childId)
        openFile(
            target = target,
            access = EnumSet.of(AccessMask.GENERIC_WRITE),
            disposition = SMB2CreateDisposition.FILE_CREATE,
        ).use { }
        return stat(childId) ?: XEntry(
            id = childId,
            name = name,
            isDir = false,
            mime = FileTypes.mimeOf(name),
            kind = EntryKind.FILE,
        )
    }

    override fun mkdir(parentDir: XEntry, name: String): XEntry {
        requireSafeEntryName(name)
        val childId = XId.child(parentDir, name)
        val target = target(childId)
        withShare(target.connection) { it.mkdir(toSmbPath(target.path)) }
        return stat(childId) ?: XEntry(
            id = childId,
            name = name,
            isDir = true,
            kind = EntryKind.DIR,
        )
    }

    override fun delete(entry: XEntry) {
        val target = target(entry.id)
        withShare(target.connection) { share ->
            if (entry.isDir) share.rmdir(toSmbPath(target.path), true)
            else share.rm(toSmbPath(target.path))
        }
    }

    override fun rename(entry: XEntry, newName: String): XEntry {
        requireSafeEntryName(newName)
        val target = target(entry.id)
        val oldPath = toSmbPath(target.path)
        val parentPath = target.path.substringBeforeLast('/', "")
        val newPath = toSmbPath(if (parentPath.isEmpty()) newName else "$parentPath/$newName")
        withShare(target.connection) { share ->
            val options = if (entry.isDir) {
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
            } else {
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            }
            share.open(
                oldPath,
                EnumSet.of(AccessMask.DELETE),
                EnumSet.noneOf(FileAttributes::class.java),
                SHARE_ACCESS,
                SMB2CreateDisposition.FILE_OPEN,
                options,
            ).use { it.rename(newPath) }
        }
        val parentId = XId.parent(entry.id) ?: return entry
        val newId = "$parentId/$newName"
        return stat(newId) ?: entry.copy(
            id = newId,
            name = newName,
            mime = if (entry.isDir) null else FileTypes.mimeOf(newName),
        )
    }

    /**
     * Moves [entry] to [destDir] using an SMB server-side rename when both endpoints resolve
     * to the same server/share. Returns false for cross-server/share moves so the operation
     * engine can fall back to streaming copy + delete.
     */
    fun moveWithinShare(entry: XEntry, destDir: XEntry): Boolean {
        val source = target(entry.id)
        val destination = target(XId.child(destDir, entry.name))
        if (!sameShare(source.connection, destination.connection)) return false

        val oldPath = toSmbPath(source.path)
        val newPath = toSmbPath(destination.path)
        withShare(source.connection) { share ->
            val options = if (entry.isDir) {
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE)
            } else {
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            }
            share.open(
                oldPath,
                EnumSet.of(AccessMask.DELETE),
                EnumSet.noneOf(FileAttributes::class.java),
                SHARE_ACCESS,
                SMB2CreateDisposition.FILE_OPEN,
                options,
            ).use { it.rename(newPath) }
        }
        return true
    }

    override fun canWrite(entry: XEntry): Boolean = entry.id != ROOT_ID

    private fun connectionEntry(config: SmbConnectionConfig): XEntry = XEntry(
        id = "$scheme://${config.id}",
        name = config.name,
        isDir = true,
        kind = EntryKind.DIR,
        badge = config.uncPath,
        canRead = true,
        canWrite = true,
    )

    private data class Target(
        val connection: SmbConnectionConfig,
        /** Path inside the actual SMB share, including the configured starting directory. */
        val path: String,
    )

    private fun target(id: String): Target {
        require(id.startsWith("$scheme://") && id != ROOT_ID) { "Invalid SMB id: $id" }
        val raw = id.removePrefix("$scheme://").trimEnd('/')
        val connectionId = raw.substringBefore('/')
        val connection = connections.find(connectionId)
            ?: throw IOException("SMB connection is no longer configured")
        val relativePath = raw.substringAfter('/', "")
        val path = when {
            connection.basePath.isBlank() -> relativePath
            relativePath.isBlank() -> connection.basePath
            else -> "${connection.basePath}/$relativePath"
        }
        return Target(connection, path)
    }

    private fun sameShare(first: SmbConnectionConfig, second: SmbConnectionConfig): Boolean =
        first.port == second.port &&
            first.host.equals(second.host, ignoreCase = true) &&
            first.share.equals(second.share, ignoreCase = true)

    private fun <T> withShare(config: SmbConnectionConfig, block: (DiskShare) -> T): T {
        val client = SMBClient()
        try {
            val connection = client.connect(config.host, config.port)
            val auth = if (config.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(
                    config.username,
                    connections.password(config.id).toCharArray(),
                    config.domain.takeIf { it.isNotBlank() },
                )
            }
            val session = connection.authenticate(auth)
            val connected = session.connectShare(config.share)
            if (connected !is DiskShare) {
                runCatching { connected.close() }
                throw IOException("SMB share '${config.share}' is not a disk share")
            }
            connected.use { return block(it) }
        } catch (error: IOException) {
            throw error
        } catch (error: Throwable) {
            throw IOException(error.message ?: "SMB operation failed", error)
        } finally {
            runCatching { client.close() }
        }
    }

    private class OpenFileHandle(
        val client: SMBClient,
        val share: DiskShare,
        val file: SmbFile,
    ) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            runCatching { file.close() }
            runCatching { share.close() }
            runCatching { client.close() }
        }
    }

    private fun openFile(
        target: Target,
        access: EnumSet<AccessMask>,
        disposition: SMB2CreateDisposition,
    ): OpenFileHandle {
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
                toSmbPath(target.path),
                access,
                EnumSet.noneOf(FileAttributes::class.java),
                SHARE_ACCESS,
                disposition,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            return OpenFileHandle(client, connected, file)
        } catch (error: Throwable) {
            runCatching { client.close() }
            if (error is IOException) throw error
            throw IOException(error.message ?: "SMB file open failed", error)
        }
    }

    companion object {
        const val ROOT_ID = "smb://"

        private val SHARE_ACCESS: EnumSet<SMB2ShareAccess> = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2ShareAccess.FILE_SHARE_DELETE,
        )

        /**
         * Verifies host reachability, authentication, share access, and the configured start path.
         * Nothing is saved and no remote content is modified.
         */
        fun testConnection(config: SmbConnectionConfig, password: String) {
            val client = SMBClient()
            try {
                val connection = client.connect(config.host, config.port)
                val auth = if (config.username.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        config.username,
                        password.toCharArray(),
                        config.domain.takeIf { it.isNotBlank() },
                    )
                }
                val session = connection.authenticate(auth)
                val connected = session.connectShare(config.share)
                if (connected !is DiskShare) {
                    runCatching { connected.close() }
                    throw IOException("SMB share '${config.share}' is not a disk share")
                }
                connected.use { share ->
                    // Listing is intentional: it proves the optional basePath exists and is readable.
                    share.list(toSmbPath(config.basePath))
                }
            } catch (error: IOException) {
                throw error
            } catch (error: Throwable) {
                throw IOException(error.message ?: "SMB connection test failed", error)
            } finally {
                runCatching { client.close() }
            }
        }

        fun rootEntry(): XEntry = XEntry(
            id = ROOT_ID,
            name = "SMB",
            isDir = true,
            kind = EntryKind.DIR,
            canRead = true,
            canWrite = false,
            badge = "SMB2 / SMB3",
        )

        private fun toSmbPath(path: String): String = path.replace('/', '\\')
    }
}

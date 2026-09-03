package app.local1st.files.core.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.SmbRandomAccessOutputFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min

/**
 * Seekable SMB bridge for other Android apps.
 *
 * Normal URIs are temporary read grants. Output URIs are created explicitly by XFiles after the
 * user chooses an SMB destination. They point at a freshly-created hidden partial file, support
 * random-access writes for containers such as MP4, and are committed with update(commit=true)
 * only after the caller finishes successfully. delete() aborts and removes the partial file.
 */
class RemoteFileProvider : ContentProvider() {
    private val callbackThread by lazy {
        HandlerThread("XFiles-remote-file").apply { start() }
    }
    private val callbackHandler by lazy { Handler(callbackThread.looper) }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        uri.getQueryParameter(PARAM_MIME)
            ?.takeIf { it.isNotBlank() }
            ?: FileTypes.mimeOf(displayName(uri))

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireSmbId(uri)
        val requested = projection ?: DEFAULT_PROJECTION
        val columns = requested.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
        val cursor = MatrixCursor(columns.toTypedArray(), 1)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(displayName(uri))
                OpenableColumns.SIZE -> row.add(fileSize(uri))
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return if (isOutputUri(uri)) openOutputFile(uri, mode) else openReadFile(uri, mode)
    }

    private fun openReadFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Remote input files are read-only")
        val id = requireSmbId(uri)
        val size = resolvedSize(uri, id)
        val remote = try {
            SmbRandomAccessFile.open(id, Graph.smbConnections)
        } catch (error: Throwable) {
            throw FileNotFoundException(error.message ?: "Unable to open SMB file").also {
                it.initCause(error)
            }
        }
        val callback = object : ProxyFileDescriptorCallback() {
            private var currentRemote = remote
            private var released = false

            override fun onGetSize(): Long = size

            @Synchronized
            override fun onRead(offset: Long, requestedSize: Int, data: ByteArray): Int {
                if (released || offset < 0L || requestedSize <= 0 || data.isEmpty()) return 0
                if (offset >= size) return 0
                val remaining = size - offset
                val count = min(min(requestedSize, data.size).toLong(), remaining).toInt()
                if (count <= 0) return 0

                var total = 0
                var reconnects = 0
                while (total < count) {
                    val read = try {
                        currentRemote.read(
                            offset + total,
                            data,
                            total,
                            count - total,
                        )
                    } catch (error: Throwable) {
                        if (reconnects >= MAX_RECONNECTS) {
                            throw ErrnoException("SMB read", OsConstants.EIO, error)
                        }
                        reconnects += 1
                        runCatching { currentRemote.close() }
                        currentRemote = try {
                            SmbRandomAccessFile.open(id, Graph.smbConnections)
                        } catch (reopenError: Throwable) {
                            if (reconnects >= MAX_RECONNECTS) {
                                throw ErrnoException("SMB reconnect", OsConstants.EIO, reopenError)
                            }
                            continue
                        }
                        continue
                    }
                    if (read <= 0) break
                    total += read
                }
                return total
            }

            @Synchronized
            override fun onRelease() {
                if (released) return
                released = true
                currentRemote.close()
            }
        }
        return openProxy(ParcelFileDescriptor.MODE_READ_ONLY, callback, remote::close)
    }

    private fun openOutputFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('w')) throw FileNotFoundException("Output URI requires write access")
        val id = requireSmbId(uri)
        val remote = try {
            SmbRandomAccessOutputFile.open(id, Graph.smbConnections)
        } catch (error: Throwable) {
            throw FileNotFoundException(error.message ?: "Unable to open SMB output").also {
                it.initCause(error)
            }
        }
        val callback = object : ProxyFileDescriptorCallback() {
            private var currentRemote = remote
            // Output URIs always point at a partial file created immediately before the grant.
            private var knownSize = 0L
            private var released = false

            override fun onGetSize(): Long = knownSize

            @Synchronized
            override fun onRead(offset: Long, requestedSize: Int, data: ByteArray): Int {
                if (released || offset < 0L || requestedSize <= 0 || data.isEmpty()) return 0
                if (offset >= knownSize) return 0
                val count = min(min(requestedSize, data.size).toLong(), knownSize - offset).toInt()
                if (count <= 0) return 0
                return withReconnect("SMB output read") { handle ->
                    handle.read(offset, data, 0, count).coerceAtLeast(0)
                }
            }

            @Synchronized
            override fun onWrite(offset: Long, requestedSize: Int, data: ByteArray): Int {
                if (released || offset < 0L || requestedSize <= 0 || data.isEmpty()) return 0
                val count = min(requestedSize, data.size)
                val written = withReconnect("SMB output write") { handle ->
                    handle.write(offset, data, 0, count)
                }
                if (written > 0) knownSize = maxOf(knownSize, offset + written)
                return written
            }

            private fun <T> withReconnect(label: String, block: (SmbRandomAccessOutputFile) -> T): T {
                var reconnects = 0
                while (true) {
                    try {
                        return block(currentRemote)
                    } catch (error: Throwable) {
                        if (reconnects >= MAX_RECONNECTS) {
                            throw ErrnoException(label, OsConstants.EIO, error)
                        }
                        reconnects += 1
                        runCatching { currentRemote.close() }
                        currentRemote = try {
                            SmbRandomAccessOutputFile.open(id, Graph.smbConnections)
                        } catch (reopenError: Throwable) {
                            if (reconnects >= MAX_RECONNECTS) {
                                throw ErrnoException("SMB output reconnect", OsConstants.EIO, reopenError)
                            }
                            continue
                        }
                    }
                }
            }

            @Synchronized
            override fun onRelease() {
                if (released) return
                released = true
                currentRemote.close()
            }
        }
        return openProxy(ParcelFileDescriptor.MODE_READ_WRITE, callback, remote::close)
    }

    private fun openProxy(
        mode: Int,
        callback: ProxyFileDescriptorCallback,
        closeOnFailure: () -> Unit,
    ): ParcelFileDescriptor = try {
        val storage = requireNotNull(context?.getSystemService(StorageManager::class.java))
        storage.openProxyFileDescriptor(mode, callback, callbackHandler)
    } catch (error: Throwable) {
        closeOnFailure()
        throw FileNotFoundException(error.message ?: "Unable to expose SMB file").also {
            it.initCause(error)
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, mode), 0L, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Insert is not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        if (!isOutputUri(uri)) throw UnsupportedOperationException("Remote input files are read-only")
        val id = requireSmbId(uri)
        val fs = Graph.fsRegistry.forId(id)
        val entry = fs.stat(id) ?: return 0
        fs.delete(entry)
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (!isOutputUri(uri)) throw UnsupportedOperationException("Remote input files are read-only")
        if (values?.getAsBoolean(KEY_COMMIT) != true) return 0
        val id = requireSmbId(uri)
        val finalName = uri.getQueryParameter(PARAM_FINAL_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing final output name")
        val fs = Graph.fsRegistry.forId(id)
        val entry = fs.stat(id) ?: throw IOException("Partial output no longer exists")
        val parentId = XId.parent(id) ?: throw IOException("Output parent is missing")
        val finalId = "${parentId.trimEnd('/')}/$finalName"
        if (fs.stat(finalId) != null) throw IOException("同名のファイルが既にあります: $finalName")
        fs.rename(entry, finalName)
        return 1
    }

    private fun requireSmbId(uri: Uri): String {
        val id = uri.getQueryParameter(PARAM_ID)
            ?: throw FileNotFoundException("Missing remote file id")
        if (XId.schemeOf(id) != XId.SCHEME_SMB) {
            throw FileNotFoundException("Unsupported remote file scheme")
        }
        return id
    }

    private fun isOutputUri(uri: Uri): Boolean = uri.getQueryParameter(PARAM_MODE) == MODE_OUTPUT

    private fun displayName(uri: Uri): String =
        uri.getQueryParameter(PARAM_FINAL_NAME)
            ?.takeIf { isOutputUri(uri) && it.isNotBlank() }
            ?: uri.getQueryParameter(PARAM_NAME)?.takeIf { it.isNotBlank() }
            ?: "remote-file"

    private fun fileSize(uri: Uri): Long =
        uri.getQueryParameter(PARAM_SIZE)?.toLongOrNull() ?: -1L

    private fun resolvedSize(uri: Uri, id: String): Long {
        val encoded = fileSize(uri)
        if (encoded >= 0L) return encoded
        return Graph.fsRegistry.forId(id).stat(id)?.size?.takeIf { it >= 0L }
            ?: throw FileNotFoundException("Unable to determine SMB file size")
    }

    companion object {
        const val KEY_COMMIT = "commit"
        private const val AUTHORITY_SUFFIX = ".remotefileprovider"
        private const val PARAM_ID = "id"
        private const val PARAM_NAME = "name"
        private const val PARAM_MIME = "mime"
        private const val PARAM_SIZE = "size"
        private const val PARAM_MODE = "mode"
        private const val PARAM_FINAL_NAME = "finalName"
        private const val MODE_OUTPUT = "output"
        private const val MAX_RECONNECTS = 2
        private val DEFAULT_PROJECTION = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        fun canServe(entry: XEntry): Boolean =
            !entry.isDir && entry.scheme == XId.SCHEME_SMB

        fun uriFor(context: Context, entry: XEntry): Uri {
            require(canServe(entry)) { "Unsupported remote entry: ${entry.id}" }
            return Uri.Builder()
                .scheme("content")
                .authority(context.packageName + AUTHORITY_SUFFIX)
                .appendPath("file")
                .appendQueryParameter(PARAM_ID, entry.id)
                .appendQueryParameter(PARAM_NAME, entry.name)
                .appendQueryParameter(
                    PARAM_MIME,
                    entry.mime ?: FileTypes.mimeOf(entry.name) ?: "application/octet-stream",
                )
                .appendQueryParameter(PARAM_SIZE, entry.size.toString())
                .build()
        }

        fun outputUriFor(
            context: Context,
            partialEntry: XEntry,
            finalName: String,
            mimeType: String,
        ): Uri {
            require(canServe(partialEntry)) { "Unsupported SMB output: ${partialEntry.id}" }
            require(finalName.isNotBlank()) { "finalName is required" }
            return Uri.Builder()
                .scheme("content")
                .authority(context.packageName + AUTHORITY_SUFFIX)
                .appendPath("file")
                .appendQueryParameter(PARAM_ID, partialEntry.id)
                .appendQueryParameter(PARAM_NAME, partialEntry.name)
                .appendQueryParameter(PARAM_FINAL_NAME, finalName)
                .appendQueryParameter(PARAM_MIME, mimeType.ifBlank { "application/octet-stream" })
                .appendQueryParameter(PARAM_SIZE, "0")
                .appendQueryParameter(PARAM_MODE, MODE_OUTPUT)
                .build()
        }
    }
}

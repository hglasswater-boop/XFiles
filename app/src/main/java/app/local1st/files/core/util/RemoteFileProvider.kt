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
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.io.FileNotFoundException
import kotlin.math.min

/**
 * Read-only content provider used when handing an SMB file to another Android app.
 *
 * A normal FileProvider can only expose a real local path. SMB entries do not have one, so this
 * provider presents a seekable proxy file descriptor backed by SMBJ random-access reads. That lets
 * media players seek inside large remote videos without downloading the whole file first, while
 * ACTION_VIEW / ACTION_SEND still receive an ordinary content:// URI with a temporary read grant.
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
        if (mode != "r") throw FileNotFoundException("Remote files are read-only")
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
            override fun onGetSize(): Long = size

            override fun onRead(offset: Long, requestedSize: Int, data: ByteArray): Int {
                if (offset < 0L || requestedSize <= 0 || data.isEmpty()) return 0
                if (offset >= size) return 0
                val remaining = size - offset
                val count = min(min(requestedSize, data.size).toLong(), remaining).toInt()
                if (count <= 0) return 0
                return try {
                    remote.read(offset, data, 0, count).coerceAtLeast(0)
                } catch (error: Throwable) {
                    throw ErrnoException("SMB read", OsConstants.EIO, error)
                }
            }

            override fun onRelease() {
                remote.close()
            }
        }
        return try {
            val storage = requireNotNull(context?.getSystemService(StorageManager::class.java))
            storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                callback,
                callbackHandler,
            )
        } catch (error: Throwable) {
            remote.close()
            throw FileNotFoundException(error.message ?: "Unable to expose SMB file").also {
                it.initCause(error)
            }
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor =
        AssetFileDescriptor(openFile(uri, mode), 0L, AssetFileDescriptor.UNKNOWN_LENGTH)

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Remote files are read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Remote files are read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Remote files are read-only")

    private fun requireSmbId(uri: Uri): String {
        val id = uri.getQueryParameter(PARAM_ID)
            ?: throw FileNotFoundException("Missing remote file id")
        if (XId.schemeOf(id) != XId.SCHEME_SMB) {
            throw FileNotFoundException("Unsupported remote file scheme")
        }
        return id
    }

    private fun displayName(uri: Uri): String =
        uri.getQueryParameter(PARAM_NAME)?.takeIf { it.isNotBlank() } ?: "remote-file"

    private fun fileSize(uri: Uri): Long =
        uri.getQueryParameter(PARAM_SIZE)?.toLongOrNull() ?: -1L

    private fun resolvedSize(uri: Uri, id: String): Long {
        val encoded = fileSize(uri)
        if (encoded >= 0L) return encoded
        return Graph.fsRegistry.forId(id).stat(id)?.size?.takeIf { it >= 0L }
            ?: throw FileNotFoundException("Unable to determine SMB file size")
    }

    companion object {
        private const val AUTHORITY_SUFFIX = ".remotefileprovider"
        private const val PARAM_ID = "id"
        private const val PARAM_NAME = "name"
        private const val PARAM_MIME = "mime"
        private const val PARAM_SIZE = "size"
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
    }
}

package app.local1st.files.ui.viewer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.io.IOException
import kotlin.math.min

/** Seekable Media3 source backed by SMBJ's offset-based reads. */
@UnstableApi
class SmbDataSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var file: SmbRandomAccessFile? = null
    private var position = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        check(file == null) { "DataSource is already open" }
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            if (dataSpec.uri.scheme != XId.SCHEME_SMB) {
                throw IOException("Unsupported SMB URI: ${dataSpec.uri}")
            }
            file = SmbRandomAccessFile.open(dataSpec.uri.toString(), Graph.smbConnections)
            position = dataSpec.position
            bytesRemaining = dataSpec.length
            transferStarted(dataSpec)
            opened = true
            return bytesRemaining
        } catch (error: Throwable) {
            closeResources()
            uri = null
            throw when (error) {
                is IOException -> error
                is Error -> error
                else -> DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requested = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(bytesRemaining, length.toLong()).toInt()
        }
        val count = try {
            checkNotNull(file).read(position, buffer, offset, requested)
        } catch (error: Throwable) {
            closeAfterReadFailure(error)
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        position += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        var failure: Throwable? = null
        try {
            file?.close()
        } catch (error: Throwable) {
            failure = error
        }
        file = null
        position = 0L
        bytesRemaining = C.LENGTH_UNSET.toLong()
        if (opened) {
            opened = false
            try {
                transferEnded()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let {
            throw if (it is IOException) it
            else DataSourceException(it, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
    }

    private fun closeResources() {
        runCatching { file?.close() }
        file = null
        position = 0L
        bytesRemaining = C.LENGTH_UNSET.toLong()
        opened = false
    }

    private fun closeAfterReadFailure(cause: Throwable): Nothing {
        val failure = if (cause is IOException) cause
        else DataSourceException(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

/** Base source used by DefaultDataSource for XFiles-specific URI schemes. */
@UnstableApi
class XFilesRemoteDataSource : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(delegate == null) { "DataSource is already open" }
        val source = when (dataSpec.uri.scheme) {
            XId.SCHEME_ROOT -> PrivilegedDataSource()
            XId.SCHEME_SMB -> SmbDataSource()
            else -> throw DataSourceException(
                IOException("Unsupported XFiles media URI: ${dataSpec.uri}"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        }
        listeners.forEach(source::addTransferListener)
        delegate = source
        return try {
            source.open(dataSpec)
        } catch (error: Throwable) {
            runCatching { source.close() }
            delegate = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(delegate).read(buffer, offset, length)

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        val source = delegate
        delegate = null
        source?.close()
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = XFilesRemoteDataSource()
    }
}

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
import java.util.LinkedHashMap
import kotlin.math.min

/**
 * Seekable Media3 source backed by SMBJ's offset-based reads.
 *
 * Media3/extractors commonly ask a DataSource for relatively small chunks. Forwarding every one of
 * those reads to the NAS makes playback latency-bound, especially when the SMB server is not on a
 * near-zero-latency network. Read aligned 2 MiB regions instead and keep the two hottest regions in
 * memory. Sequential playback then consumes many Media3 reads from one SMB transfer, while a seek
 * drops straight onto a new region without reading from the old position first.
 */
@UnstableApi
class SmbDataSource : BaseDataSource(false) {
    private var uri: Uri? = null
    private var file: SmbRandomAccessFile? = null
    private var position = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_CACHED_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_CACHED_BLOCKS
    }

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
            blocks.clear()
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

        val copied = try {
            readBuffered(position, buffer, offset, requested)
        } catch (error: Throwable) {
            closeAfterReadFailure(error)
        }
        if (copied <= 0) return C.RESULT_END_OF_INPUT

        position += copied
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= copied
        bytesTransferred(copied)
        return copied
    }

    private fun readBuffered(
        sourcePosition: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int {
        var remotePosition = sourcePosition
        var destOffset = destinationOffset
        var remaining = length
        var copied = 0

        while (remaining > 0) {
            val blockStart = (remotePosition / BLOCK_SIZE) * BLOCK_SIZE
            val block = blocks[blockStart] ?: loadBlock(blockStart).also { blocks[blockStart] = it }
            if (block.isEmpty()) break

            val inBlock = (remotePosition - blockStart).toInt()
            if (inBlock >= block.size) break
            val count = min(remaining, block.size - inBlock)
            block.copyInto(
                destination = destination,
                destinationOffset = destOffset,
                startIndex = inBlock,
                endIndex = inBlock + count,
            )
            remotePosition += count
            destOffset += count
            remaining -= count
            copied += count
        }
        return copied
    }

    private fun loadBlock(blockStart: Long): ByteArray {
        val data = ByteArray(BLOCK_SIZE)
        val handle = checkNotNull(file)
        var filled = 0
        while (filled < data.size) {
            val count = handle.read(
                blockStart + filled,
                data,
                filled,
                data.size - filled,
            )
            if (count <= 0) break
            filled += count
        }
        return when {
            filled == 0 -> ByteArray(0)
            filled == data.size -> data
            else -> data.copyOf(filled)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        blocks.clear()
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
        blocks.clear()
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

    private companion object {
        const val BLOCK_SIZE = 2 * 1024 * 1024
        const val MAX_CACHED_BLOCKS = 2
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

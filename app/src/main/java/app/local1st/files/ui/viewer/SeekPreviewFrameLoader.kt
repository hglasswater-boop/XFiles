package app.local1st.files.ui.viewer

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.di.Graph
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Extracts the small frame shown above the video seek bar.
 *
 * Only one extraction is allowed at a time. Scrubbing can produce many target changes and native
 * MediaMetadataRetriever work is not reliably cancellable; serializing it prevents rapid slider
 * movement from opening several decoders / SMB readers at once.
 */
internal object SeekPreviewFrameLoader {
    private val semaphore = Semaphore(1)

    suspend fun load(entry: XEntry, targetMs: Long): Bitmap? = semaphore.withPermit {
        withContext(Dispatchers.IO) { extract(entry, targetMs) }
    }

    private fun extract(entry: XEntry, targetMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var descriptor: ParcelFileDescriptor? = null
        var smbSource: SeekPreviewSmbDataSource? = null
        return try {
            when {
                entry.scheme == XId.SCHEME_SMB -> {
                    smbSource = SeekPreviewSmbDataSource(entry)
                    retriever.setDataSource(smbSource)
                }
                entry.localPath != null -> retriever.setDataSource(entry.localPath)
                entry.scheme == XId.SCHEME_ROOT -> {
                    // Preview decoration must never trigger a new root prompt/probe.
                    val transport = PrivilegedAccess.fdTransport() ?: return null
                    descriptor = transport.openFd(entry.path, write = false) ?: return null
                    retriever.setDataSource(descriptor.fileDescriptor)
                }
                else -> retriever.setDataSource(entry.path)
            }

            val timeUs = targetMs.coerceAtLeast(0L) * 1000L
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    PREVIEW_WIDTH,
                    PREVIEW_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let(::scaleDown)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { descriptor?.close() }
            runCatching { smbSource?.close() }
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= PREVIEW_WIDTH && src.height <= PREVIEW_HEIGHT) return src
        val scale = minOf(
            PREVIEW_WIDTH.toFloat() / src.width,
            PREVIEW_HEIGHT.toFloat() / src.height,
        )
        val out = Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (out !== src) src.recycle()
        return out
    }

    private const val PREVIEW_WIDTH = 320
    private const val PREVIEW_HEIGHT = 180
}

/** SMB random-access bridge with the same read-ahead strategy as normal remote thumbnails. */
private class SeekPreviewSmbDataSource(private val entry: XEntry) : MediaDataSource() {
    private var file: SmbRandomAccessFile? = null
    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_CACHED_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_CACHED_BLOCKS
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || position >= entry.size || size <= 0) return -1
        val wanted = minOf(size.toLong(), entry.size - position).toInt()
        if (wanted <= 0) return -1

        var remotePosition = position
        var destOffset = offset
        var remaining = wanted
        var copied = 0
        while (remaining > 0) {
            val blockStart = (remotePosition / BLOCK_SIZE) * BLOCK_SIZE
            val block = blocks[blockStart] ?: loadBlock(blockStart).also { blocks[blockStart] = it }
            if (block.isEmpty()) break
            val inBlock = (remotePosition - blockStart).toInt()
            if (inBlock >= block.size) break
            val count = minOf(remaining, block.size - inBlock)
            block.copyInto(buffer, destOffset, inBlock, inBlock + count)
            remotePosition += count
            destOffset += count
            remaining -= count
            copied += count
        }
        return if (copied > 0) copied else -1
    }

    override fun getSize(): Long = entry.size

    @Synchronized
    override fun close() {
        blocks.clear()
        runCatching { file?.close() }
        file = null
    }

    private fun loadBlock(blockStart: Long): ByteArray {
        val remainingFile = entry.size - blockStart
        if (remainingFile <= 0L) return ByteArray(0)
        val blockLength = minOf(BLOCK_SIZE.toLong(), remainingFile).toInt()
        val data = ByteArray(blockLength)
        val handle = file ?: SmbRandomAccessFile.open(entry.id, Graph.smbConnections).also { file = it }

        var filled = 0
        while (filled < blockLength) {
            val count = handle.read(blockStart + filled, data, filled, blockLength - filled)
            if (count <= 0) break
            filled += count
        }
        return if (filled == data.size) data else data.copyOf(filled)
    }

    private companion object {
        const val BLOCK_SIZE = 1024 * 1024
        const val MAX_CACHED_BLOCKS = 4
    }
}

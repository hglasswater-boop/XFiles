package app.local1st.files.core.thumb

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
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

/** Resolves video duration only for thumbnails that actually display it. */
object VideoDurationResolver {
    private data class CacheKey(val id: String, val mtime: Long, val size: Long)

    private const val FAILED = -1L
    private const val MAX_CACHE_ENTRIES = 256
    private val semaphore = Semaphore(3)
    private val cache = object : LinkedHashMap<CacheKey, Long>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Long>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun durationMs(entry: XEntry): Long? {
        val key = CacheKey(entry.id, entry.mtime, entry.size)
        synchronized(cache) {
            cache[key]?.let { return it.takeIf { value -> value > 0L } }
        }
        return semaphore.withPermit {
            synchronized(cache) {
                cache[key]?.let { return@withPermit it.takeIf { value -> value > 0L } }
            }
            val value = withContext(Dispatchers.IO) { readDuration(entry) }
            synchronized(cache) { cache[key] = value ?: FAILED }
            value
        }
    }

    private fun readDuration(entry: XEntry): Long? {
        val retriever = MediaMetadataRetriever()
        var descriptor: ParcelFileDescriptor? = null
        var smbSource: DurationSmbMediaDataSource? = null
        return try {
            when {
                entry.scheme == XId.SCHEME_SMB -> {
                    if (entry.size <= 0L) return null
                    val source = DurationSmbMediaDataSource(entry)
                    smbSource = source
                    retriever.setDataSource(source)
                }
                entry.localPath != null -> retriever.setDataSource(entry.localPath)
                entry.scheme == XId.SCHEME_ROOT -> {
                    val transport = PrivilegedAccess.fdTransport() ?: return null
                    val fd = transport.openFd(entry.path, write = false) ?: return null
                    descriptor = fd
                    retriever.setDataSource(fd.fileDescriptor)
                }
                else -> return null
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { descriptor?.close() }
            runCatching { smbSource?.close() }
        }
    }
}

/** Random-access SMB bridge with small read-ahead for MP4 metadata parsing. */
private class DurationSmbMediaDataSource(private val entry: XEntry) : MediaDataSource() {
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

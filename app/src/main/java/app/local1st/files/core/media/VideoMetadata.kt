package app.local1st.files.core.media

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.di.Graph
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Technical video properties shown in Details and on thumbnail overlays. */
data class VideoMetadata(
    val width: Int?,
    val height: Int?,
    val frameRate: Double?,
    val durationMs: Long?,
    val codec: String?,
    val bitrate: Long?,
)

/**
 * Reads only the video track headers, never decodes a frame. Results are cached by file identity
 * so the thumbnail duration badge and Details dialog share one metadata read. SMB parsing uses
 * aligned read-ahead blocks to avoid turning MediaExtractor's small random reads into many NAS
 * round trips.
 */
object VideoMetadataReader {
    private data class CacheValue(val metadata: VideoMetadata?)

    private const val MAX_CACHE_ENTRIES = 512
    private val locks = Array(16) { Mutex() }
    private val cache = object : LinkedHashMap<String, CacheValue>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheValue>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun read(entry: XEntry): VideoMetadata? {
        if (entry.isDir) return null
        val key = cacheKey(entry)
        synchronized(cache) { cache[key]?.let { return it.metadata } }
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
        return lock.withLock {
            synchronized(cache) { cache[key]?.let { return@withLock it.metadata } }
            val metadata = withContext(Dispatchers.IO) { readBlocking(entry) }
            synchronized(cache) { cache[key] = CacheValue(metadata) }
            metadata
        }
    }

    private fun cacheKey(entry: XEntry): String = "${entry.id}|${entry.mtime}|${entry.size}"

    private fun readBlocking(entry: XEntry): VideoMetadata? {
        val extractor = MediaExtractor()
        var descriptor: ParcelFileDescriptor? = null
        var remoteSource: SmbMetadataDataSource? = null
        try {
            when {
                entry.localPath != null -> extractor.setDataSource(entry.localPath)
                entry.scheme == XId.SCHEME_FILE -> extractor.setDataSource(entry.path)
                entry.scheme == XId.SCHEME_ROOT -> {
                    val transport = PrivilegedAccess.fdTransport() ?: return null
                    descriptor = transport.openFd(entry.path, write = false) ?: return null
                    extractor.setDataSource(descriptor.fileDescriptor)
                }
                entry.scheme == XId.SCHEME_SMB -> {
                    if (entry.size <= 0L) return null
                    remoteSource = SmbMetadataDataSource(entry)
                    extractor.setDataSource(remoteSource)
                }
                entry.scheme == "content" -> {
                    extractor.setDataSource(Graph.appContext, Uri.parse(entry.id), null)
                }
                else -> return null
            }

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.stringOrNull(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue

                var width = format.intOrNull(MediaFormat.KEY_WIDTH)
                var height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                val rotation = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
                if ((rotation == 90 || rotation == 270) && width != null && height != null) {
                    val originalWidth = width
                    width = height
                    height = originalWidth
                }

                val frameRate = format.doubleOrNull(MediaFormat.KEY_FRAME_RATE)
                    ?.takeIf { it > 0.0 && it < 1000.0 }
                val durationMs = format.longOrNull(MediaFormat.KEY_DURATION)
                    ?.takeIf { it > 0L }
                    ?.div(1000L)
                val bitrate = format.longOrNull(MediaFormat.KEY_BIT_RATE)
                    ?.takeIf { it > 0L }

                return VideoMetadata(
                    width = width?.takeIf { it > 0 },
                    height = height?.takeIf { it > 0 },
                    frameRate = frameRate,
                    durationMs = durationMs,
                    codec = codecLabel(mime),
                    bitrate = bitrate,
                )
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { extractor.release() }
            runCatching { remoteSource?.close() }
            runCatching { descriptor?.close() }
        }
    }

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? {
        if (!containsKey(key)) return null
        return runCatching { getLong(key) }.getOrNull()
            ?: runCatching { getInteger(key).toLong() }.getOrNull()
    }

    private fun MediaFormat.doubleOrNull(key: String): Double? {
        if (!containsKey(key)) return null
        return runCatching { getFloat(key).toDouble() }.getOrNull()
            ?: runCatching { getInteger(key).toDouble() }.getOrNull()
    }
}

fun formatVideoDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatVideoFrameRate(frameRate: Double): String {
    val rounded = kotlin.math.round(frameRate)
    return if (kotlin.math.abs(frameRate - rounded) < 0.01) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.2f", frameRate).trimEnd('0').trimEnd('.')
    }
}

fun formatVideoBitrate(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
    bitsPerSecond >= 1_000L -> String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1_000.0)
    else -> "$bitsPerSecond bps"
}

private fun codecLabel(mime: String): String = when (mime.lowercase(Locale.US)) {
    "video/avc" -> "H.264 / AVC"
    "video/hevc" -> "H.265 / HEVC"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4 Visual"
    "video/mpeg2" -> "MPEG-2"
    "video/3gpp" -> "H.263"
    else -> mime
}

/** Random-access MediaDataSource backed by SMB with read-ahead for metadata parsing. */
private class SmbMetadataDataSource(private val entry: XEntry) : MediaDataSource() {
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
        var destinationOffset = offset
        var remaining = wanted
        var copied = 0
        while (remaining > 0) {
            val blockStart = (remotePosition / BLOCK_SIZE) * BLOCK_SIZE
            val block = blocks[blockStart] ?: loadBlock(blockStart).also { blocks[blockStart] = it }
            if (block.isEmpty()) break
            val inBlock = (remotePosition - blockStart).toInt()
            if (inBlock >= block.size) break
            val count = minOf(remaining, block.size - inBlock)
            block.copyInto(buffer, destinationOffset, inBlock, inBlock + count)
            remotePosition += count
            destinationOffset += count
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

package app.local1st.files.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.di.Graph
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Video poster-frame model for SMB entries. */
data class RemoteVideoThumb(val entry: XEntry)

class RemoteVideoThumbFetcher(
    private val context: Context,
    private val data: RemoteVideoThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cached = cacheFile(context, data.entry)
        if (cached.length() > 0L) {
            return SourceFetchResult(
                source = ImageSource(cached.toOkioPath(), FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }
        return semaphore.withPermit {
            if (cached.length() > 0L) {
                return@withPermit SourceFetchResult(
                    source = ImageSource(cached.toOkioPath(), FileSystem.SYSTEM),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK,
                )
            }
            val bitmap = withContext(Dispatchers.IO) { extractFrame(data.entry) }
                ?: return@withPermit null
            runCatching {
                cached.parentFile?.mkdirs()
                val tmp = File.createTempFile("remote-thumb", ".tmp", cached.parentFile)
                val ok = tmp.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it)
                }
                if (ok) {
                    if (!tmp.renameTo(cached)) {
                        cached.delete()
                        tmp.renameTo(cached)
                    }
                } else {
                    tmp.delete()
                }
            }
            ImageFetchResult(
                image = bitmap.asImage(),
                isSampled = true,
                dataSource = DataSource.NETWORK,
            )
        }
    }

    private fun extractFrame(entry: XEntry): Bitmap? {
        if (entry.size <= 0L) return null
        val retriever = MediaMetadataRetriever()
        val source = SmbMediaDataSource(entry)
        return try {
            retriever.setDataSource(source)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_SIZE,
                    THUMB_SIZE,
                ) ?: retriever.getScaledFrameAtTime(
                    -1L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_SIZE,
                    THUMB_SIZE,
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let(::scaleDown)
                    ?: retriever.getFrameAtTime(-1L)?.let(::scaleDown)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= THUMB_SIZE) return src
        val scale = THUMB_SIZE.toFloat() / maxDim
        val out = Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (out !== src) src.recycle()
        return out
    }

    class Factory(private val context: Context) : Fetcher.Factory<RemoteVideoThumb> {
        override fun create(
            data: RemoteVideoThumb,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = RemoteVideoThumbFetcher(context, data)
    }

    class Key : Keyer<RemoteVideoThumb> {
        override fun key(data: RemoteVideoThumb, options: Options): String = with(data.entry) {
            "remote-video-thumb-v3:$id:$mtime:$size"
        }
    }

    companion object {
        // The list renders thumbnails around 36dp. 192px is enough even on dense screens and
        // trims decoder/compression work compared with the previous 256px poster.
        private const val THUMB_SIZE = 192

        // NAS latency dominates thumbnail startup, so generate several visible rows at once.
        // Four keeps the UI responsive without opening an excessive number of SMB sessions.
        private val semaphore = Semaphore(4)

        private fun cacheFile(context: Context, entry: XEntry): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("v3|${entry.id}|${entry.mtime}|${entry.size}".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(File(context.cacheDir, "remote_video_thumbs"), "$digest.jpg")
        }
    }
}

/**
 * Random-access bridge for MediaMetadataRetriever with SMB read-ahead.
 *
 * MediaMetadataRetriever performs many small readAt() calls while parsing MP4 atoms. Sending every
 * one as a separate SMB request is extremely expensive on a NAS. Read 1 MiB aligned blocks instead
 * and keep the four most recently used blocks in memory. Header, tail metadata and nearby frame
 * reads then usually cost one SMB round trip per region rather than dozens/hundreds of tiny reads.
 */
private class SmbMediaDataSource(private val entry: XEntry) : MediaDataSource() {
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
            block.copyInto(
                destination = buffer,
                destinationOffset = destOffset,
                startIndex = inBlock,
                endIndex = inBlock + count,
            )
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
            val count = handle.read(
                blockStart + filled,
                data,
                filled,
                blockLength - filled,
            )
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

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
            val bitmap = withContext(Dispatchers.IO) { extractFrame(data.entry) } ?: return@withPermit null
            runCatching {
                cached.parentFile?.mkdirs()
                val tmp = File.createTempFile("remote-thumb", ".tmp", cached.parentFile)
                val ok = tmp.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it)
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
            "remote-video-thumb-v2:$id:$mtime:$size"
        }
    }

    companion object {
        private const val THUMB_SIZE = 256
        private val semaphore = Semaphore(2)

        private fun cacheFile(context: Context, entry: XEntry): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("v2|${entry.id}|${entry.mtime}|${entry.size}".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(File(context.cacheDir, "remote_video_thumbs"), "$digest.jpg")
        }
    }
}

/** True random-access bridge for MediaMetadataRetriever backed by SMBJ offset reads. */
private class SmbMediaDataSource(private val entry: XEntry) : MediaDataSource() {
    private var file: SmbRandomAccessFile? = null

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || position >= entry.size || size <= 0) return -1
        val requested = minOf(size.toLong(), entry.size - position).toInt()
        if (requested <= 0) return -1
        val handle = file ?: SmbRandomAccessFile.open(entry.id, Graph.smbConnections).also { file = it }
        return handle.read(position, buffer, offset, requested)
    }

    override fun getSize(): Long = entry.size

    @Synchronized
    override fun close() {
        runCatching { file?.close() }
        file = null
    }
}

package app.local1st.files.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.priv.PrivilegedAccess
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Coil model for a video file's poster frame. [mtime]/[size] are part of the cache key,
 * so an overwritten video naturally invalidates its stale thumbnail.
 */
data class VideoThumb(
    val path: String,
    val mtime: Long,
    val size: Long,
    val privileged: Boolean = false,
)

/**
 * Extracts a high-quality poster frame from a local video and keeps it in an on-disk thumbnail
 * cache. Embedded cover/jacket art is preferred when the container exposes one; only videos
 * without usable artwork fall back to a decoded representative frame.
 *
 * Frame extraction spins up a hardware codec and can take seconds for big videos, so results
 * must survive process death — Coil's own disk cache only stores source data (which here would
 * be the whole video), never decoded frames.
 *
 * Entries for videos that were deleted or renamed are not cleaned up eagerly; they sit
 * in the app-private, OS-clearable cacheDir until pruning reclaims them.
 */
class VideoThumbFetcher(
    private val context: Context,
    private val data: VideoThumb,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cached = cacheFile(context, data)
        when (val read = readCached(cached)) {
            is CacheRead.Hit -> return read.result
            CacheRead.Failed -> return null
            CacheRead.Miss -> {}
        }
        return extractSemaphore.withPermit {
            when (val read = readCached(cached)) {
                is CacheRead.Hit -> return@withPermit read.result
                CacheRead.Failed -> return@withPermit null
                CacheRead.Miss -> {}
            }
            val bitmap = extractFrame(data)
            writeCache(cached, bitmap)
            bitmap?.let {
                ImageFetchResult(image = it.asImage(), isSampled = true, dataSource = DataSource.DISK)
            }
        }
    }

    private sealed interface CacheRead {
        class Hit(val result: FetchResult) : CacheRead
        object Failed : CacheRead
        object Miss : CacheRead
    }

    private fun readCached(cached: File): CacheRead {
        val len = cached.length()
        if (len == 0L) {
            if (!cached.isFile) return CacheRead.Miss
            if (System.currentTimeMillis() - cached.lastModified() < NEGATIVE_TTL_MS) {
                return CacheRead.Failed
            }
            cached.delete()
            return CacheRead.Miss
        }
        if (!isValidJpeg(cached, len)) {
            cached.delete()
            return CacheRead.Miss
        }
        return CacheRead.Hit(
            SourceFetchResult(
                source = ImageSource(cached.toOkioPath(), FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            ),
        )
    }

    private fun isValidJpeg(file: File, len: Long): Boolean = runCatching {
        if (len < 4) return false
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(2)
            raf.readFully(head)
            raf.seek(len - 2)
            val tail = ByteArray(2)
            raf.readFully(tail)
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
                tail[0] == 0xFF.toByte() && tail[1] == 0xD9.toByte()
        }
    }.getOrDefault(false)

    private fun extractFrame(data: VideoThumb): Bitmap? {
        val retriever = MediaMetadataRetriever()
        var descriptor: ParcelFileDescriptor? = null
        val releaseLock = Any()
        fun release() {
            synchronized(releaseLock) { runCatching { retriever.release() } }
        }
        val watchdog = watchdogExecutor.schedule({ release() }, EXTRACT_TIMEOUT_S, TimeUnit.SECONDS)
        return try {
            if (data.privileged) {
                val transport = PrivilegedAccess.fdTransport() ?: return null
                descriptor = transport.openFd(data.path, write = false) ?: return null
                retriever.setDataSource(descriptor.fileDescriptor)
            } else {
                retriever.setDataSource(data.path)
            }

            retriever.embeddedPicture
                ?.let(::decodeEmbeddedPicture)
                ?.let { return it }

            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    -1,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMB_SIZE,
                    THUMB_SIZE,
                )
            } else {
                retriever.getFrameAtTime(-1)?.let(::scaleDown)
            }
        } catch (_: Exception) {
            null
        } finally {
            watchdog.cancel(false)
            release()
            runCatching { descriptor?.close() }
        }
    }

    private fun decodeEmbeddedPicture(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > THUMB_SIZE * 2 ||
            bounds.outHeight / sample > THUMB_SIZE * 2
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::scaleDown)
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

    private fun writeCache(target: File, bitmap: Bitmap?) {
        runCatching {
            val dir = target.parentFile ?: return
            pruneMaybe(dir)
            if (bitmap == null) {
                target.createNewFile()
                return
            }
            val tmp = File.createTempFile("thumb", ".tmp", dir)
            val ok = tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (!ok || !tmp.renameTo(target)) tmp.delete()
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoThumbFetcher(context, data)
    }

    class Key : Keyer<VideoThumb> {
        override fun key(data: VideoThumb, options: Options): String =
            "video-thumb-v3:${data.path}:${data.mtime}:${data.size}"
    }

    companion object {
        private const val THUMB_SIZE = 512
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        private const val NEGATIVE_TTL_MS = 60L * 60 * 1000
        private const val EXTRACT_TIMEOUT_S = 20L
        private const val PRUNE_EVERY_WRITES = 512
        private const val PRUNE_SKIP_RECENT_MS = 5L * 60 * 1000

        private val extractSemaphore = Semaphore(2)
        private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "video-thumb-watchdog").apply { isDaemon = true }
        }

        private var writesUntilPrune = 1

        private fun cacheFile(context: Context, data: VideoThumb): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("v3|${data.path}|${data.mtime}|${data.size}".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            val dir = File(context.cacheDir, "video_thumbs")
            dir.mkdirs()
            return File(dir, "$digest.jpg")
        }

        @Synchronized
        private fun pruneMaybe(dir: File) {
            if (--writesUntilPrune > 0) return
            writesUntilPrune = PRUNE_EVERY_WRITES
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            val now = System.currentTimeMillis()
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE_BYTES / 2) break
                if (now - f.lastModified() < PRUNE_SKIP_RECENT_MS) continue
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }
}

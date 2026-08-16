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
 * Frame extraction is serialized because MediaMetadataRetriever is not thread-safe. Unlike the
 * original implementation, the retriever and its SMB random-access source stay alive while the
 * same video is being scrubbed. This avoids reparsing the container and reopening the NAS file for
 * every slider movement. Recently decoded frames are also retained in a tiny in-memory LRU cache.
 */
internal object SeekPreviewFrameLoader {
    private val semaphore = Semaphore(1)
    private var session: PreviewSession? = null

    suspend fun load(entry: XEntry, targetMs: Long): Bitmap? = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            val normalizedTarget = normalizeTarget(entry, targetMs)
            val active = sessionFor(entry) ?: return@withContext null
            active.cached(normalizedTarget)?.let { return@withContext it }
            active.extract(normalizedTarget)?.also { active.cache(normalizedTarget, it) }
        }
    }

    /** Close the previous video's resources when another video starts being previewed. */
    private fun sessionFor(entry: XEntry): PreviewSession? {
        val key = sessionKey(entry)
        session?.let { current ->
            if (current.key == key) return current
            current.close()
            session = null
        }
        return PreviewSession.create(entry, key)?.also { session = it }
    }

    private fun normalizeTarget(entry: XEntry, targetMs: Long): Long {
        val bucket = if (entry.scheme == XId.SCHEME_SMB) SMB_PREVIEW_BUCKET_MS else LOCAL_PREVIEW_BUCKET_MS
        val safe = targetMs.coerceAtLeast(0L)
        return (safe / bucket) * bucket
    }

    private fun sessionKey(entry: XEntry): String = "${entry.id}|${entry.mtime}|${entry.size}"

    private const val LOCAL_PREVIEW_BUCKET_MS = 500L
    private const val SMB_PREVIEW_BUCKET_MS = 1_000L
    private const val PREVIEW_WIDTH = 320
    private const val PREVIEW_HEIGHT = 180
    private const val MAX_CACHED_FRAMES = 18

    private class PreviewSession private constructor(
        val key: String,
        private val retriever: MediaMetadataRetriever,
        private val descriptor: ParcelFileDescriptor?,
        private val smbSource: SeekPreviewSmbDataSource?,
    ) {
        private val frames = object : LinkedHashMap<Long, Bitmap>(MAX_CACHED_FRAMES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?): Boolean =
                size > MAX_CACHED_FRAMES
        }

        fun cached(targetMs: Long): Bitmap? = frames[targetMs]

        fun cache(targetMs: Long, bitmap: Bitmap) {
            frames[targetMs] = bitmap
        }

        fun extract(targetMs: Long): Bitmap? {
            val timeUs = targetMs.coerceAtLeast(0L) * 1000L
            return try {
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
            }
        }

        fun close() {
            frames.clear()
            runCatching { retriever.release() }
            runCatching { descriptor?.close() }
            runCatching { smbSource?.close() }
        }

        companion object {
            fun create(entry: XEntry, key: String): PreviewSession? {
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
                            val transport = PrivilegedAccess.fdTransport()
                            if (transport == null) {
                                runCatching { retriever.release() }
                                return null
                            }
                            descriptor = transport.openFd(entry.path, write = false)
                            if (descriptor == null) {
                                runCatching { retriever.release() }
                                return null
                            }
                            retriever.setDataSource(descriptor.fileDescriptor)
                        }
                        else -> retriever.setDataSource(entry.path)
                    }
                    PreviewSession(key, retriever, descriptor, smbSource)
                } catch (_: Exception) {
                    runCatching { retriever.release() }
                    runCatching { descriptor?.close() }
                    runCatching { smbSource?.close() }
                    null
                }
            }
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
}

/**
 * SMB random-access bridge for seek previews.
 *
 * The bridge now lives for the whole preview session instead of one frame, so the open SMB handle
 * and read-ahead blocks are reused across consecutive slider positions. A larger 12 MiB block LRU
 * covers MP4 header/sample-table reads plus several nearby keyframe regions without excessive NAS
 * round trips.
 */
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
        const val MAX_CACHED_BLOCKS = 12
    }
}

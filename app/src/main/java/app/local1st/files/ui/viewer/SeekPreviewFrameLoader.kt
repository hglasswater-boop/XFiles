package app.local1st.files.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.prefs.SeekPreviewSettings
import app.local1st.files.di.Graph
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Extracts the small frame shown above the video seek bar.
 *
 * The retriever and SMB random-access source stay alive while the same video is being scrubbed, so
 * the container is not reparsed and the NAS file is not reopened for every position. Preview frames
 * are stored as compressed JPEG bytes rather than live Bitmaps, allowing a few minutes around the
 * current target to be prefetched without consuming tens of megabytes of Bitmap heap.
 */
internal object SeekPreviewFrameLoader {
    private val semaphore = Semaphore(1)
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null
    private var session: PreviewSession? = null

    suspend fun load(entry: XEntry, targetMs: Long): Bitmap? {
        val normalizedTarget = normalizeTarget(targetMs)
        val bitmap = semaphore.withPermit {
            withContext(Dispatchers.IO) {
                val active = sessionFor(entry) ?: return@withContext null
                active.cached(normalizedTarget)?.let { return@withContext it }
                active.extract(normalizedTarget)?.also { active.cache(normalizedTarget, it) }
            }
        }
        schedulePrefetch(entry, normalizedTarget)
        return bitmap
    }

    private fun schedulePrefetch(entry: XEntry, centerMs: Long) {
        prefetchJob?.cancel()
        val radiusMinutes = SeekPreviewSettings.currentPrefetchMinutes(Graph.appContext)
        if (radiusMinutes <= 0) return
        prefetchJob = prefetchScope.launch {
            val durationMs = semaphore.withPermit {
                sessionFor(entry)?.durationMs ?: 0L
            }
            prefetchAround(
                entry = entry,
                centerMs = centerMs,
                durationMs = durationMs,
                radiusMinutes = radiusMinutes,
            )
        }
    }

    /**
     * Fills the configured range nearest-first. Cancellation is checked between every frame so a new
     * thumb position immediately takes priority over obsolete background prefetch work.
     */
    private suspend fun prefetchAround(
        entry: XEntry,
        centerMs: Long,
        durationMs: Long,
        radiusMinutes: Int,
    ) {
        if (radiusMinutes <= 0) return
        val center = normalizeTarget(centerMs)
        val radiusSeconds = radiusMinutes.coerceIn(0, MAX_PREFETCH_MINUTES) * 60
        for (step in 1..radiusSeconds) {
            currentCoroutineContext().ensureActive()
            val delta = step * PREVIEW_BUCKET_MS
            val before = center - delta
            if (before >= 0L) prefetchOne(entry, before)

            currentCoroutineContext().ensureActive()
            val after = center + delta
            if (durationMs <= 0L || after <= durationMs) prefetchOne(entry, after)
        }
    }

    private suspend fun prefetchOne(entry: XEntry, targetMs: Long) = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            val active = sessionFor(entry) ?: return@withContext
            val target = normalizeTarget(targetMs)
            if (active.hasCached(target)) return@withContext
            active.extract(target)?.let { bitmap ->
                active.cache(target, bitmap)
                bitmap.recycle()
            }
        }
    }

    /** Close the previous video's resources when another video starts being previewed. */
    private fun sessionFor(entry: XEntry): PreviewSession? {
        val key = sessionKey(entry)
        session?.let { current ->
            if (current.key == key) return current
            prefetchJob?.cancel()
            current.close()
            session = null
        }
        return PreviewSession.create(entry, key)?.also { session = it }
    }

    private fun normalizeTarget(targetMs: Long): Long {
        val safe = targetMs.coerceAtLeast(0L)
        return (safe / PREVIEW_BUCKET_MS) * PREVIEW_BUCKET_MS
    }

    private fun sessionKey(entry: XEntry): String = "${entry.id}|${entry.mtime}|${entry.size}"

    private const val PREVIEW_BUCKET_MS = 1_000L
    private const val PREVIEW_WIDTH = 320
    private const val PREVIEW_HEIGHT = 180
    private const val JPEG_QUALITY = 72
    private const val MAX_COMPRESSED_CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_PREFETCH_MINUTES = 5

    private class PreviewSession private constructor(
        val key: String,
        private val retriever: MediaMetadataRetriever,
        val durationMs: Long,
        private val descriptor: ParcelFileDescriptor?,
        private val smbSource: SeekPreviewSmbDataSource?,
    ) {
        private val frames = LinkedHashMap<Long, ByteArray>(256, 0.75f, true)
        private var cachedBytes = 0

        fun hasCached(targetMs: Long): Boolean = frames.containsKey(targetMs)

        fun cached(targetMs: Long): Bitmap? {
            val encoded = frames[targetMs] ?: return null
            return BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        }

        fun cache(targetMs: Long, bitmap: Bitmap) {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return
            val encoded = output.toByteArray()
            frames.put(targetMs, encoded)?.let { cachedBytes -= it.size }
            cachedBytes += encoded.size
            trimCache()
        }

        private fun trimCache() {
            val iterator = frames.entries.iterator()
            while (cachedBytes > MAX_COMPRESSED_CACHE_BYTES && iterator.hasNext()) {
                val eldest = iterator.next()
                cachedBytes -= eldest.value.size
                iterator.remove()
            }
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
            cachedBytes = 0
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
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(0L)
                        ?: 0L
                    PreviewSession(key, retriever, durationMs, descriptor, smbSource)
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
 * The bridge lives for the whole preview session so the open SMB handle and a small read-ahead LRU
 * are reused. Large read-ahead is deliberately avoided: timeline scrubbing usually jumps between
 * distant keyframe regions, where retaining many old 1 MiB blocks provides little benefit.
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
        const val MAX_CACHED_BLOCKS = 4
    }
}

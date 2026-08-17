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
import kotlin.math.abs
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
 * Two cache modes are available while scrubbing:
 * - hybrid: a hot LRU of decoded Bitmaps around the active position plus a wider JPEG cache;
 * - all-Bitmap: every frame in the configured prefetch window stays decoded in RAM and JPEG
 *   compression/decompression is skipped entirely.
 *
 * The retriever and SMB random-access source stay alive while the same video is being scrubbed, so
 * the container is not reparsed and the NAS file is not reopened for every position.
 */
internal object SeekPreviewFrameLoader {
    private val semaphore = Semaphore(1)
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null
    private var prefetchCenterMs = Long.MIN_VALUE
    private var prefetchRadiusMinutes = -1
    private var prefetchKeepAllBitmaps = false
    private var prefetchSessionKey: String? = null
    private var session: PreviewSession? = null

    /** Main-thread-safe exact-frame lookup. No IO or JPEG decode is performed here. */
    @Synchronized
    fun peekHot(entry: XEntry, targetMs: Long): Bitmap? {
        val active = session ?: return null
        if (active.key != sessionKey(entry)) return null
        return active.hotCached(normalizeTarget(targetMs))
    }

    suspend fun load(entry: XEntry, targetMs: Long): Bitmap? {
        val normalizedTarget = normalizeTarget(targetMs)
        val keepAllBitmaps = SeekPreviewSettings.currentKeepAllBitmaps(Graph.appContext)
        val bitmap = semaphore.withPermit {
            withContext(Dispatchers.IO) {
                val active = sessionFor(entry) ?: return@withContext null
                active.cached(normalizedTarget, promoteHot = true)?.let { return@withContext it }
                active.extract(normalizedTarget)?.also {
                    active.cache(
                        normalizedTarget,
                        it,
                        keepHot = true,
                        storeCompressed = !keepAllBitmaps,
                    )
                }
            }
        }
        schedulePrefetch(entry, normalizedTarget)
        return bitmap
    }

    /**
     * Keep a stable prefetch window while the finger jitters around the same timeline area.
     * The center moves only after the requested frame leaves the inner half of the configured
     * prefetch range. For example, a ±2 minute cache keeps the same center for ±1 minute of thumb
     * movement, so tiny slider motion does not repeatedly cancel useful background work.
     */
    private fun schedulePrefetch(entry: XEntry, centerMs: Long) {
        val radiusMinutes = SeekPreviewSettings.currentPrefetchMinutes(Graph.appContext)
        val keepAllBitmaps = SeekPreviewSettings.currentKeepAllBitmaps(Graph.appContext)
        if (radiusMinutes <= 0) {
            prefetchJob?.cancel()
            resetPrefetchWindow()
            return
        }

        val key = sessionKey(entry)
        val radiusMs = radiusMinutes.toLong() * 60_000L
        val recenterThresholdMs = maxOf(PREVIEW_BUCKET_MS * 2, radiusMs / 2)
        val sameWindow = prefetchSessionKey == key &&
            prefetchRadiusMinutes == radiusMinutes &&
            prefetchKeepAllBitmaps == keepAllBitmaps &&
            prefetchCenterMs != Long.MIN_VALUE &&
            abs(centerMs - prefetchCenterMs) <= recenterThresholdMs
        if (sameWindow) return

        prefetchJob?.cancel()
        prefetchCenterMs = centerMs
        prefetchRadiusMinutes = radiusMinutes
        prefetchKeepAllBitmaps = keepAllBitmaps
        prefetchSessionKey = key
        prefetchJob = prefetchScope.launch {
            val durationMs = semaphore.withPermit {
                sessionFor(entry)?.durationMs ?: 0L
            }
            prefetchAround(
                entry = entry,
                centerMs = centerMs,
                durationMs = durationMs,
                radiusMinutes = radiusMinutes,
                keepAllBitmaps = keepAllBitmaps,
            )
        }
    }

    /**
     * Fills the configured range nearest-first. In hybrid mode the closest ~30 seconds stay decoded
     * as Bitmaps and farther frames are retained as JPEG bytes. In all-Bitmap mode every prefetched
     * second stays decoded, avoiding both JPEG work and black/loading flashes for cached positions.
     */
    private suspend fun prefetchAround(
        entry: XEntry,
        centerMs: Long,
        durationMs: Long,
        radiusMinutes: Int,
        keepAllBitmaps: Boolean,
    ) {
        if (radiusMinutes <= 0) return
        val center = normalizeTarget(centerMs)
        val radiusSeconds = radiusMinutes.coerceIn(0, MAX_PREFETCH_MINUTES) * 60
        for (step in 1..radiusSeconds) {
            currentCoroutineContext().ensureActive()
            val keepHot = keepAllBitmaps || step <= HOT_PREFETCH_SECONDS
            val storeCompressed = !keepAllBitmaps
            val delta = step * PREVIEW_BUCKET_MS
            val before = center - delta
            if (before >= 0L) {
                prefetchOne(
                    entry = entry,
                    targetMs = before,
                    keepHot = keepHot,
                    storeCompressed = storeCompressed,
                )
            }

            currentCoroutineContext().ensureActive()
            val after = center + delta
            if (durationMs <= 0L || after <= durationMs) {
                prefetchOne(
                    entry = entry,
                    targetMs = after,
                    keepHot = keepHot,
                    storeCompressed = storeCompressed,
                )
            }
        }
    }

    private suspend fun prefetchOne(
        entry: XEntry,
        targetMs: Long,
        keepHot: Boolean,
        storeCompressed: Boolean,
    ) = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            val active = sessionFor(entry) ?: return@withContext
            val target = normalizeTarget(targetMs)
            if (active.hasHot(target)) return@withContext
            if (active.hasCompressed(target)) {
                if (keepHot) active.cached(target, promoteHot = true)
                return@withContext
            }
            active.extract(target)?.let { bitmap ->
                active.cache(
                    targetMs = target,
                    bitmap = bitmap,
                    keepHot = keepHot,
                    storeCompressed = storeCompressed,
                )
                if (!keepHot) bitmap.recycle()
            }
        }
    }

    /** Close the previous video's resources when another video starts being previewed. */
    @Synchronized
    private fun sessionFor(entry: XEntry): PreviewSession? {
        val key = sessionKey(entry)
        session?.let { current ->
            if (current.key == key) return current
            prefetchJob?.cancel()
            resetPrefetchWindow()
            current.close()
            session = null
        }
        return PreviewSession.create(entry, key)?.also { session = it }
    }

    private fun resetPrefetchWindow() {
        prefetchCenterMs = Long.MIN_VALUE
        prefetchRadiusMinutes = -1
        prefetchKeepAllBitmaps = false
        prefetchSessionKey = null
    }

    private fun normalizeTarget(targetMs: Long): Long {
        val safe = targetMs.coerceAtLeast(0L)
        return (safe / PREVIEW_BUCKET_MS) * PREVIEW_BUCKET_MS
    }

    private fun hotBitmapLimit(): Int {
        if (!SeekPreviewSettings.currentKeepAllBitmaps(Graph.appContext)) return MAX_HOT_BITMAPS
        val radiusMinutes = SeekPreviewSettings.currentPrefetchMinutes(Graph.appContext)
            .coerceIn(0, MAX_PREFETCH_MINUTES)
        if (radiusMinutes <= 0) return MAX_HOT_BITMAPS
        return radiusMinutes * 60 * 2 + 1
    }

    private fun sessionKey(entry: XEntry): String = "${entry.id}|${entry.mtime}|${entry.size}"

    private const val PREVIEW_BUCKET_MS = 1_000L
    private const val PREVIEW_WIDTH = 320
    private const val PREVIEW_HEIGHT = 180
    private const val JPEG_QUALITY = 72
    private const val MAX_COMPRESSED_CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_PREFETCH_MINUTES = 5
    private const val HOT_PREFETCH_SECONDS = 30
    private const val MAX_HOT_BITMAPS = 64

    private class PreviewSession private constructor(
        val key: String,
        private val retriever: MediaMetadataRetriever,
        val durationMs: Long,
        private val descriptor: ParcelFileDescriptor?,
        private val smbSource: SeekPreviewSmbDataSource?,
    ) {
        private val frames = LinkedHashMap<Long, ByteArray>(256, 0.75f, true)
        private val hotFrames = LinkedHashMap<Long, Bitmap>(256, 0.75f, true)
        private var cachedBytes = 0

        @Synchronized
        fun hasHot(targetMs: Long): Boolean = hotFrames.containsKey(targetMs)

        @Synchronized
        fun hasCompressed(targetMs: Long): Boolean = frames.containsKey(targetMs)

        @Synchronized
        fun hotCached(targetMs: Long): Bitmap? = hotFrames[targetMs]

        @Synchronized
        fun cached(targetMs: Long, promoteHot: Boolean): Bitmap? {
            hotFrames[targetMs]?.let { return it }
            val encoded = frames[targetMs] ?: return null
            val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: return null
            if (promoteHot) {
                hotFrames[targetMs] = bitmap
                trimHotCache()
            }
            return bitmap
        }

        @Synchronized
        fun cache(
            targetMs: Long,
            bitmap: Bitmap,
            keepHot: Boolean,
            storeCompressed: Boolean,
        ) {
            if (storeCompressed) {
                val output = ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    val encoded = output.toByteArray()
                    frames.put(targetMs, encoded)?.let { cachedBytes -= it.size }
                    cachedBytes += encoded.size
                    trimCompressedCache()
                }
            }
            if (keepHot) {
                hotFrames[targetMs] = bitmap
                trimHotCache()
            }
        }

        private fun trimCompressedCache() {
            val iterator = frames.entries.iterator()
            while (cachedBytes > MAX_COMPRESSED_CACHE_BYTES && iterator.hasNext()) {
                val eldest = iterator.next()
                cachedBytes -= eldest.value.size
                iterator.remove()
            }
        }

        private fun trimHotCache() {
            val limit = hotBitmapLimit()
            val iterator = hotFrames.entries.iterator()
            while (hotFrames.size > limit && iterator.hasNext()) {
                iterator.next()
                // Do not recycle here: Compose may still be presenting the just-evicted Bitmap.
                // Dropping the map reference lets Android reclaim it safely once the UI lets go.
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

        @Synchronized
        fun close() {
            frames.clear()
            hotFrames.clear()
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

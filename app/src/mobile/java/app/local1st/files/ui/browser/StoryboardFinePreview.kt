package app.local1st.files.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.local1st.files.R
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.media.formatVideoDuration
import app.local1st.files.di.Graph
import coil3.compose.AsyncImage
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val FINE_FRAME_COUNT = 11
private const val FINE_WIDTH = 480
private const val FINE_HEIGHT = 270
private const val FINE_JPEG_QUALITY = 84
private const val FINE_CACHE_VERSION = 1
private const val FINE_MAX_CACHE_BYTES = 48L * 1024L * 1024L
private const val FINE_EXTRACT_TIMEOUT_SECONDS = 90L

internal fun storyboardFineStepMs(
    frames: List<StoryboardFrame>,
    frameIndex: Int,
): Long {
    if (frames.isEmpty() || frameIndex !in frames.indices) return 1_000L
    val center = frames[frameIndex].timeMs
    val gaps = buildList {
        frames.getOrNull(frameIndex - 1)?.timeMs?.let { previous ->
            (center - previous).takeIf { it > 0L }?.let(::add)
        }
        frames.getOrNull(frameIndex + 1)?.timeMs?.let { next ->
            (next - center).takeIf { it > 0L }?.let(::add)
        }
    }
    val coarseSpacingMs = gaps.minOrNull() ?: 10_000L
    return (coarseSpacingMs / 10L).coerceIn(250L, 5_000L)
}

private sealed interface FineStoryboardUiState {
    data object Loading : FineStoryboardUiState
    data class Ready(
        val result: StoryboardResult,
        val complete: Boolean,
    ) : FineStoryboardUiState
    data object Failed : FineStoryboardUiState
}

/**
 * Fine-grained storyboard content that can be embedded at the bottom of the current screen.
 * Keeping the loader and cache behind this composable lets Cast, the local player and the browser
 * share the same precise previews without opening another modal surface.
 */
@Composable
internal fun StoryboardFinePreviewPanel(
    entry: XEntry,
    centerTimeMs: Long,
    stepMs: Long,
    durationMs: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    currentPositionMs: Long? = null,
) {
    val context = LocalContext.current
    val state by produceState<FineStoryboardUiState>(
        initialValue = FineStoryboardUiState.Loading,
        entry.id,
        entry.mtime,
        entry.size,
        centerTimeMs,
        stepMs,
        durationMs,
    ) {
        var emittedProgress = false
        val result = runCatching {
            FineStoryboardLoader.load(
                context = context,
                entry = entry,
                centerTimeMs = centerTimeMs,
                stepMs = stepMs,
                durationHintMs = durationMs,
            ) { partial ->
                emittedProgress = true
                value = FineStoryboardUiState.Ready(partial, complete = false)
            }
        }
        value = result.fold(
            onSuccess = { FineStoryboardUiState.Ready(it, complete = true) },
            onFailure = {
                if (emittedProgress) value else FineStoryboardUiState.Failed
            },
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
        color = Color.Black.copy(alpha = 0.24f),
        contentColor = Color.White,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.16f)),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = formatVideoDuration(centerTimeMs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            when (val current = state) {
                FineStoryboardUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 86.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(Modifier.size(26.dp))
                    }
                }

                FineStoryboardUiState.Failed -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 76.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.64f),
                        )
                    }
                }

                is FineStoryboardUiState.Ready -> {
                    val frames = current.result.frames
                    val centerIndex = remember(frames, centerTimeMs) {
                        frames.indices.minByOrNull { index ->
                            abs(frames[index].timeMs - centerTimeMs)
                        } ?: 0
                    }
                    val playbackIndex = remember(frames, currentPositionMs, stepMs) {
                        val playback = currentPositionMs
                        if (playback == null || frames.isEmpty()) {
                            -1
                        } else {
                            val nearest = frames.indices.minByOrNull { index ->
                                abs(frames[index].timeMs - playback)
                            } ?: -1
                            nearest.takeIf { index ->
                                index >= 0 && abs(frames[index].timeMs - playback) <= stepMs / 2L
                            } ?: -1
                        }
                    }
                    val firstVisibleIndex = (centerIndex - 1).coerceAtLeast(0)
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = firstVisibleIndex,
                    )
                    LaunchedEffect(centerTimeMs, frames.size, firstVisibleIndex) {
                        listState.scrollToItem(firstVisibleIndex)
                    }
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        items(frames, key = { it.timeMs }) { frame ->
                            val image = frame.file?.takeIf { it.isFile && it.length() > 0L }
                            val selected = abs(frame.timeMs - centerTimeMs) <= stepMs / 2L
                            val playingNow = frame.index == playbackIndex
                            val shape = RoundedCornerShape(9.dp)
                            Box(
                                modifier = Modifier
                                    .width(132.dp)
                                    .aspectRatio(16f / 9f)
                                    .clickable(enabled = image != null) {
                                        onSelect(frame.timeMs)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (image != null) {
                                    AsyncImage(
                                        model = image,
                                        contentDescription = formatVideoDuration(frame.timeMs),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(shape)
                                            .then(
                                                when {
                                                    playingNow -> Modifier.border(
                                                        width = 3.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = shape,
                                                    )
                                                    selected -> Modifier.border(
                                                        width = 1.dp,
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                                                        shape = shape,
                                                    )
                                                    else -> Modifier
                                                },
                                            ),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(shape)
                                            .background(Color.White.copy(alpha = 0.06f))
                                            .then(
                                                if (playingNow) {
                                                    Modifier.border(
                                                        width = 3.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = shape,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (!current.complete) {
                                            LoadingIndicator(Modifier.size(22.dp))
                                        }
                                    }
                                }

                                Text(
                                    text = formatVideoDuration(frame.timeMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        playingNow -> MaterialTheme.colorScheme.onPrimary
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> Color.White
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .background(
                                            if (playingNow) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
                                            } else {
                                                Color.Black.copy(alpha = 0.62f)
                                            },
                                            RoundedCornerShape(topEnd = 7.dp),
                                        )
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Retained for call sites that still need a standalone modal wrapper. */
@Composable
internal fun StoryboardFinePreviewDialog(
    entry: XEntry,
    centerTimeMs: Long,
    stepMs: Long,
    durationMs: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        StoryboardFinePreviewPanel(
            entry = entry,
            centerTimeMs = centerTimeMs,
            stepMs = stepMs,
            durationMs = durationMs,
            onDismiss = onDismiss,
            onSelect = onSelect,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 920.dp),
        )
    }
}

private object FineStoryboardLoader {
    private val semaphore = Semaphore(1)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "fine-storyboard-watchdog").apply { isDaemon = true }
    }

    suspend fun load(
        context: Context,
        entry: XEntry,
        centerTimeMs: Long,
        stepMs: Long,
        durationHintMs: Long?,
        onProgress: suspend (StoryboardResult) -> Unit,
    ): StoryboardResult = semaphore.withPermit {
        val cacheDir = cacheDir(context, entry)
        val cachedDuration = readDuration(cacheDir)
        val duration = durationHintMs?.takeIf { it > 0L } ?: cachedDuration
        val times = fineTimes(centerTimeMs, stepMs, duration)
        val cached = withContext(Dispatchers.IO) {
            readCached(cacheDir, duration, times)
        }
        if (cached != null) return@withPermit cached
        generate(context, entry, cacheDir, centerTimeMs, stepMs, duration, onProgress)
    }

    private fun fineTimes(centerTimeMs: Long, stepMs: Long, durationMs: Long?): List<Long> {
        val safeCenter = centerTimeMs.coerceAtLeast(0L)
        val safeStep = stepMs.coerceIn(250L, 5_000L)
        val half = FINE_FRAME_COUNT / 2
        val maxTime = durationMs?.takeIf { it > 0L }?.minus(1L)?.coerceAtLeast(0L)
        return (0 until FINE_FRAME_COUNT)
            .map { index ->
                val offset = (index - half).toLong() * safeStep
                val raw = safeCenter + offset
                if (maxTime != null) raw.coerceIn(0L, maxTime) else raw.coerceAtLeast(0L)
            }
            .distinct()
    }

    private fun readDuration(cacheDir: File): Long? =
        File(cacheDir, "duration.txt").takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }

    private fun readCached(
        cacheDir: File,
        durationMs: Long?,
        times: List<Long>,
    ): StoryboardResult? {
        if (times.isEmpty()) return null
        val files = times.map { frameFile(cacheDir, it) }
        if (files.any { !it.isFile || it.length() <= 0L }) return null
        cacheDir.setLastModified(System.currentTimeMillis())
        return StoryboardResult(
            durationMs = durationMs,
            frames = times.mapIndexed { index, timeMs ->
                StoryboardFrame(index = index, timeMs = timeMs, file = files[index])
            },
        )
    }

    private suspend fun generate(
        context: Context,
        entry: XEntry,
        cacheDir: File,
        centerTimeMs: Long,
        stepMs: Long,
        durationHintMs: Long?,
        onProgress: suspend (StoryboardResult) -> Unit,
    ): StoryboardResult = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        var descriptor: ParcelFileDescriptor? = null
        var remoteSource: FineStoryboardSmbMediaDataSource? = null
        val releaseLock = Any()
        var released = false
        fun releaseRetriever() {
            synchronized(releaseLock) {
                if (!released) {
                    released = true
                    runCatching { retriever.release() }
                }
            }
        }
        val watchdogTask = watchdog.schedule(
            { releaseRetriever() },
            FINE_EXTRACT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )

        try {
            when {
                entry.scheme == XId.SCHEME_SMB -> {
                    remoteSource = FineStoryboardSmbMediaDataSource(entry)
                    retriever.setDataSource(remoteSource)
                }
                entry.localPath != null -> retriever.setDataSource(entry.localPath)
                else -> {
                    val transport = PrivilegedAccess.fdTransport()
                        ?: return@withContext StoryboardResult(durationHintMs, emptyList())
                    descriptor = transport.openFd(entry.path, write = false)
                        ?: return@withContext StoryboardResult(durationHintMs, emptyList())
                    retriever.setDataSource(descriptor.fileDescriptor)
                }
            }

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: durationHintMs?.takeIf { it > 0L }
            if (durationMs != null) {
                runCatching { File(cacheDir, "duration.txt").writeText(durationMs.toString()) }
            }

            val times = fineTimes(centerTimeMs, stepMs, durationMs)
            val frames = times.mapIndexed { index, timeMs ->
                val target = frameFile(cacheDir, timeMs)
                StoryboardFrame(
                    index = index,
                    timeMs = timeMs,
                    file = target.takeIf { it.isFile && it.length() > 0L },
                )
            }.toMutableList()

            emitProgress(onProgress, StoryboardResult(durationMs, frames.toList()))

            val centerIndex = frames.indices.minByOrNull { index ->
                abs(frames[index].timeMs - centerTimeMs)
            } ?: 0
            val extractionOrder = frames.indices.sortedBy { index -> abs(index - centerIndex) }
            for (index in extractionOrder) {
                if (frames[index].file != null) continue
                val timeMs = frames[index].timeMs
                val target = frameFile(cacheDir, timeMs)
                val bitmap = extractPreciseFrame(retriever, timeMs)
                val written = bitmap?.let { writeFrame(target, it) } == true
                bitmap?.recycle()
                frames[index] = StoryboardFrame(
                    index = index,
                    timeMs = timeMs,
                    file = target.takeIf { written },
                )
                emitProgress(onProgress, StoryboardResult(durationMs, frames.toList()))
            }

            cacheDir.setLastModified(System.currentTimeMillis())
            pruneCache(context)
            StoryboardResult(durationMs, frames.toList())
        } finally {
            watchdogTask.cancel(false)
            releaseRetriever()
            runCatching { descriptor?.close() }
            runCatching { remoteSource?.close() }
        }
    }

    private suspend fun emitProgress(
        onProgress: suspend (StoryboardResult) -> Unit,
        result: StoryboardResult,
    ) {
        withContext(Dispatchers.Main.immediate) { onProgress(result) }
    }

    private fun extractPreciseFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
    ): Bitmap? = runCatching {
        val timeUs = timeMs * 1_000L
        if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                FINE_WIDTH,
                FINE_HEIGHT,
            )
        } else {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?.let(::scaleDown)
        }
    }.getOrNull()

    private fun scaleDown(source: Bitmap): Bitmap {
        val scale = minOf(
            FINE_WIDTH.toFloat() / source.width.coerceAtLeast(1),
            FINE_HEIGHT.toFloat() / source.height.coerceAtLeast(1),
            1f,
        )
        if (scale >= 1f) return source
        val result = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (result !== source) source.recycle()
        return result
    }

    private fun writeFrame(target: File, bitmap: Bitmap): Boolean = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File.createTempFile("fine-storyboard", ".tmp", target.parentFile)
        val compressed = tmp.outputStream().buffered().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, FINE_JPEG_QUALITY, output)
        }
        if (!compressed || tmp.length() <= 0L) {
            tmp.delete()
            false
        } else {
            if (target.exists()) target.delete()
            val renamed = tmp.renameTo(target)
            if (!renamed) tmp.delete()
            renamed
        }
    }.getOrDefault(false)

    private fun cacheDir(context: Context, entry: XEntry): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(
                "v$FINE_CACHE_VERSION|${entry.id}|${entry.mtime}|${entry.size}".encodeToByteArray(),
            )
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "video_storyboard_fine"), digest)
    }

    private fun frameFile(cacheDir: File, timeMs: Long): File = File(cacheDir, "$timeMs.jpg")

    private fun pruneCache(context: Context) {
        val root = File(context.cacheDir, "video_storyboard_fine")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (total <= FINE_MAX_CACHE_BYTES) return
        for (dir in dirs.sortedBy { it.lastModified() }) {
            if (total <= FINE_MAX_CACHE_BYTES / 2L) break
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) total -= bytes
        }
    }
}

private class FineStoryboardSmbMediaDataSource(
    private val entry: XEntry,
) : MediaDataSource() {
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
            val chunk = minOf(remaining, block.size - inBlock)
            block.copyInto(
                destination = buffer,
                destinationOffset = destinationOffset,
                startIndex = inBlock,
                endIndex = inBlock + chunk,
            )
            remotePosition += chunk
            destinationOffset += chunk
            remaining -= chunk
            copied += chunk
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
        const val MAX_CACHED_BLOCKS = 16
    }
}
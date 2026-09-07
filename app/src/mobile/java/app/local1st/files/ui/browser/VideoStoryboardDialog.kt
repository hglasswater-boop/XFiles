package app.local1st.files.ui.browser

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.local1st.files.R
import app.local1st.files.core.fs.SmbRandomAccessFile
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.media.formatVideoDuration
import app.local1st.files.core.prefs.VideoStoryboardSettings
import app.local1st.files.core.thumb.isNearlyBlackVideoThumbnail
import app.local1st.files.di.Graph
import coil3.compose.AsyncImage
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val STORYBOARD_WIDTH = 384
private const val STORYBOARD_HEIGHT = 216

private data class StoryboardFrame(
    val index: Int,
    val timeMs: Long,
    val file: File?,
)

private data class StoryboardResult(
    val durationMs: Long?,
    val frames: List<StoryboardFrame>,
)

private sealed interface StoryboardUiState {
    data object Loading : StoryboardUiState
    data class Ready(
        val result: StoryboardResult,
        val complete: Boolean,
    ) : StoryboardUiState
    data object Failed : StoryboardUiState
}

@Composable
internal fun VideoStoryboardDialog(
    entry: XEntry,
    onDismiss: () -> Unit,
    onPlayFrom: (Long) -> Unit,
) {
    val context = LocalContext.current
    val sampleCount = VideoStoryboardSettings.current(context)
    val state by produceState<StoryboardUiState>(
        initialValue = StoryboardUiState.Loading,
        entry.id,
        entry.mtime,
        entry.size,
        sampleCount,
    ) {
        var emittedProgress = false
        val result = runCatching {
            StoryboardLoader.load(context, entry, sampleCount) { partial ->
                emittedProgress = true
                value = StoryboardUiState.Ready(partial, complete = false)
            }
        }
        value = result.fold(
            onSuccess = { StoryboardUiState.Ready(it, complete = true) },
            onFailure = {
                if (emittedProgress) value else StoryboardUiState.Failed
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .widthIn(max = 920.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                }

                when (val current = state) {
                    StoryboardUiState.Loading -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp),
                        ) {
                            LoadingIndicator()
                        }
                    }

                    StoryboardUiState.Failed -> StoryboardFailure(entry)

                    is StoryboardUiState.Ready -> {
                        val result = current.result
                        val readyCount = result.frames.count {
                            it.file?.isFile == true && it.file.length() > 0L
                        }
                        if (current.complete && readyCount == 0) {
                            StoryboardFailure(entry)
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(bottom = 10.dp),
                            ) {
                                result.durationMs?.takeIf { it > 0L }?.let { duration ->
                                    Text(
                                        text = formatVideoDuration(duration),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (result.frames.size < sampleCount) {
                                    Text(
                                        text = "${result.frames.size}枚（1秒間隔）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!current.complete) {
                                    LoadingIndicator(Modifier.size(18.dp))
                                    Text(
                                        text = "$readyCount/${result.frames.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 150.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 112.dp, max = 560.dp),
                            ) {
                                items(result.frames, key = { it.index }) { frame ->
                                    StoryboardFrameCard(
                                        frame = frame,
                                        loading = !current.complete && frame.file == null,
                                        onClick = {
                                            onDismiss()
                                            onPlayFrom(frame.timeMs)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryboardFailure(entry: XEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
    ) {
        Icon(
            Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.cannot_load, entry.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StoryboardFrameCard(
    frame: StoryboardFrame,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val image = frame.file?.takeIf { it.isFile && it.length() > 0L }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = image != null, onClick = onClick),
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = formatVideoDuration(frame.timeMs),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (loading) {
                    LoadingIndicator(Modifier.size(28.dp))
                } else {
                    Icon(
                        Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Text(
            text = formatVideoDuration(frame.timeMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private object StoryboardLoader {
    private const val MAX_CACHE_BYTES = 128L * 1024 * 1024
    private const val CACHE_VERSION = 4
    private const val EXTRACT_TIMEOUT_SECONDS = 120L
    private const val MIN_SAMPLE_SPACING_MS = 1_000L
    private const val JPEG_QUALITY = 82
    private const val FAST_VISIBLE_FRAME_COUNT = 4
    private val semaphore = Semaphore(1)
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "video-storyboard-watchdog").apply { isDaemon = true }
    }

    suspend fun load(
        context: Context,
        entry: XEntry,
        count: Int,
        onProgress: suspend (StoryboardResult) -> Unit,
    ): StoryboardResult = semaphore.withPermit {
        val cacheDir = cacheDir(context, entry)
        val cached = withContext(Dispatchers.IO) { readCached(cacheDir, count) }
        if (cached != null) return@withPermit cached
        generate(context, entry, cacheDir, count, onProgress)
    }

    private fun readCached(cacheDir: File, count: Int): StoryboardResult? {
        val manifest = File(cacheDir, "manifest.txt")
        if (!manifest.isFile) return null
        val durationMs = manifest.readText().trim().toLongOrNull() ?: return null
        val times = sampleTimes(durationMs, count)
        if (times.isEmpty()) return null
        val files = times.map { timeMs -> frameFile(cacheDir, timeMs) }
        if (files.any { !it.isFile || it.length() <= 0L }) return null
        cacheDir.setLastModified(System.currentTimeMillis())
        return StoryboardResult(
            durationMs = durationMs,
            frames = times.mapIndexed { index, timeMs ->
                StoryboardFrame(index, timeMs, files[index])
            },
        )
    }

    private suspend fun generate(
        context: Context,
        entry: XEntry,
        cacheDir: File,
        count: Int,
        onProgress: suspend (StoryboardResult) -> Unit,
    ): StoryboardResult = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        var descriptor: ParcelFileDescriptor? = null
        var remoteSource: StoryboardSmbMediaDataSource? = null
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
            EXTRACT_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )

        try {
            when {
                entry.scheme == XId.SCHEME_SMB -> {
                    remoteSource = StoryboardSmbMediaDataSource(entry)
                    retriever.setDataSource(remoteSource)
                }
                entry.localPath != null -> retriever.setDataSource(entry.localPath)
                else -> {
                    val transport = PrivilegedAccess.fdTransport()
                        ?: return@withContext StoryboardResult(null, emptyList())
                    descriptor = transport.openFd(entry.path, write = false)
                        ?: return@withContext StoryboardResult(null, emptyList())
                    retriever.setDataSource(descriptor.fileDescriptor)
                }
            }

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }

            if (durationMs != null) {
                runCatching { File(cacheDir, "manifest.txt").writeText(durationMs.toString()) }
            }

            val times = durationMs?.let { sampleTimes(it, count) }
                ?: fallbackTimes(count)
            val frames = times.mapIndexed { index, timeMs ->
                val target = frameFile(cacheDir, timeMs)
                StoryboardFrame(
                    index = index,
                    timeMs = timeMs,
                    file = target.takeIf { it.isFile && it.length() > 0L },
                )
            }.toMutableList()

            if (frames.any { it.file != null }) {
                emitProgress(onProgress, StoryboardResult(durationMs, frames.toList()))
            }

            for (index in extractionOrder(frames.size)) {
                if (frames[index].file != null) continue
                val timeMs = frames[index].timeMs
                val target = frameFile(cacheDir, timeMs)
                val bitmap = extractFrame(retriever, timeMs)
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
        withContext(Dispatchers.Main.immediate) {
            onProgress(result)
        }
    }

    private fun extractionOrder(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val order = LinkedHashSet<Int>(count)
        for (index in 0 until minOf(FAST_VISIBLE_FRAME_COUNT, count)) {
            order += index
        }
        if (count > FAST_VISIBLE_FRAME_COUNT) {
            order += count - 1
            order += count / 2
        }
        for (index in FAST_VISIBLE_FRAME_COUNT until count) {
            order += index
        }
        return order.toList()
    }

    private fun sampleTimes(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0L || count <= 0) return emptyList()
        val requestedCount = count.coerceAtLeast(1)
        val start = durationMs * 5L / 100L
        val end = durationMs * 95L / 100L
        val span = (end - start).coerceAtLeast(0L)
        val maxCountForSpacing = (span / MIN_SAMPLE_SPACING_MS + 1L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val actualCount = minOf(requestedCount, maxCountForSpacing)
        if (actualCount == 1) return listOf(start + span / 2L)
        return List(actualCount) { index ->
            start + (span * index / (actualCount - 1L))
        }
    }

    private fun fallbackTimes(count: Int): List<Long> {
        if (count <= 0) return emptyList()
        val anchors = listOf(5_000L, 15_000L, 30_000L, 60_000L)
        return List(count) { index ->
            if (index < anchors.size) {
                anchors[index]
            } else {
                120_000L + (index - anchors.size) * 60_000L
            }
        }
    }

    private fun extractFrame(retriever: MediaMetadataRetriever, timeMs: Long): Bitmap? {
        val timeUs = timeMs * 1_000L
        val sync = runCatching {
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    STORYBOARD_WIDTH,
                    STORYBOARD_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let(::scaleDown)
            }
        }.getOrNull()
        if (sync != null && !isNearlyBlackVideoThumbnail(sync)) return sync

        val closest = runCatching {
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    STORYBOARD_WIDTH,
                    STORYBOARD_HEIGHT,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?.let(::scaleDown)
            }
        }.getOrNull()
        if (closest != null) {
            if (closest !== sync) sync?.recycle()
            return closest
        }
        return sync
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxWidth = STORYBOARD_WIDTH
        val maxHeight = STORYBOARD_HEIGHT
        val scale = minOf(
            maxWidth.toFloat() / source.width.coerceAtLeast(1),
            maxHeight.toFloat() / source.height.coerceAtLeast(1),
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
        val tmp = File.createTempFile("storyboard", ".tmp", target.parentFile)
        val compressed = tmp.outputStream().buffered().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
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
                "v$CACHE_VERSION|${entry.id}|${entry.mtime}|${entry.size}".encodeToByteArray(),
            )
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "video_storyboards"), digest)
    }

    private fun frameFile(cacheDir: File, timeMs: Long): File =
        File(cacheDir, "$timeMs.jpg")

    private fun pruneCache(context: Context) {
        val root = File(context.cacheDir, "video_storyboards")
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
        if (total <= MAX_CACHE_BYTES) return
        for (dir in dirs.sortedBy { it.lastModified() }) {
            if (total <= MAX_CACHE_BYTES / 2L) break
            val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) total -= bytes
        }
    }
}

/**
 * One SMB random-access handle for the whole storyboard generation. MediaMetadataRetriever issues
 * small, non-linear reads around sync samples, so a bounded block cache avoids reopening the file
 * or repeating adjacent SMB reads for every thumbnail.
 */
private class StoryboardSmbMediaDataSource(private val entry: XEntry) : MediaDataSource() {
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
        const val MAX_CACHED_BLOCKS = 8
    }
}

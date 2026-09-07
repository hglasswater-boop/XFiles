package app.local1st.files.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.media.formatVideoDuration
import app.local1st.files.core.prefs.VideoStoryboardSettings
import app.local1st.files.ui.browser.StoryboardFinePreviewDialog
import app.local1st.files.ui.browser.StoryboardLoader
import app.local1st.files.ui.browser.StoryboardResult
import app.local1st.files.ui.browser.storyboardFineStepMs
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.launch

private sealed interface CastStoryboardUiState {
    data object Loading : CastStoryboardUiState
    data class Ready(
        val result: StoryboardResult,
        val complete: Boolean,
    ) : CastStoryboardUiState
    data object Failed : CastStoryboardUiState
}

/**
 * Storyboard for the remote Cast controller and local player chrome.
 *
 * Portrait Cast controls use a vertically scrolling compact-preview timeline, while landscape and
 * the local player use the compact horizontal strip. All layouts reuse the browser storyboard
 * loader and disk cache. Long-pressing a frame opens a precise, locally sampled timeline around
 * that point so the user can choose a much finer seek position without generating dense previews
 * for the entire video.
 */
@Composable
internal fun CastStoryboardStrip(
    entry: XEntry,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    vertical: Boolean,
    showJumpToCurrent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sampleCount = VideoStoryboardSettings.current(context)
    val minSpacingSeconds = VideoStoryboardSettings.currentMinSpacingSeconds(context)
    val state by produceState<CastStoryboardUiState>(
        initialValue = CastStoryboardUiState.Loading,
        entry.id,
        entry.mtime,
        entry.size,
        sampleCount,
        minSpacingSeconds,
    ) {
        var emittedProgress = false
        val result = runCatching {
            StoryboardLoader.load(
                context = context,
                entry = entry,
                count = sampleCount,
                minSpacingMs = minSpacingSeconds * 1_000L,
            ) { partial ->
                emittedProgress = true
                value = CastStoryboardUiState.Ready(partial, complete = false)
            }
        }
        value = result.fold(
            onSuccess = { CastStoryboardUiState.Ready(it, complete = true) },
            onFailure = {
                if (emittedProgress) value else CastStoryboardUiState.Failed
            },
        )
    }

    when (val current = state) {
        CastStoryboardUiState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(Modifier.size(28.dp))
            }
        }

        CastStoryboardUiState.Failed -> Unit

        is CastStoryboardUiState.Ready -> {
            val frames = current.result.frames
            if (frames.isEmpty() && current.complete) return

            val nearestIndex = remember(frames, positionMs) {
                frames.indices.minByOrNull { index ->
                    abs(frames[index].timeMs - positionMs)
                } ?: -1
            }
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var alignedToPlayback by remember(
                entry.id,
                entry.mtime,
                entry.size,
                sampleCount,
                minSpacingSeconds,
                vertical,
            ) { mutableStateOf(false) }
            var fineFrameIndex by remember(
                entry.id,
                entry.mtime,
                entry.size,
                vertical,
            ) { mutableStateOf<Int?>(null) }

            LaunchedEffect(frames.size, nearestIndex, current.complete, vertical) {
                if (alignedToPlayback || nearestIndex < 0) return@LaunchedEffect
                if (positionMs <= 0L && !current.complete) return@LaunchedEffect
                if (frames.none { it.file?.isFile == true && it.file.length() > 0L }) {
                    return@LaunchedEffect
                }
                listState.scrollToItem(nearestIndex)
                alignedToPlayback = true
            }

            Column(modifier = modifier.fillMaxSize()) {
                if (vertical) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(frames, key = { it.index }) { frame ->
                            val image = frame.file?.takeIf { it.isFile && it.length() > 0L }
                            val selected = frame.index == nearestIndex
                            val shape = RoundedCornerShape(10.dp)
                            val previewModifier = Modifier
                                .fillMaxWidth(0.48f)
                                .widthIn(max = 180.dp)
                                .aspectRatio(16f / 9f)

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        enabled = image != null,
                                        onClick = { onSeek(frame.timeMs) },
                                        onLongClick = { fineFrameIndex = frame.index },
                                    ),
                            ) {
                                Box(modifier = previewModifier) {
                                    if (image != null) {
                                        AsyncImage(
                                            model = image,
                                            contentDescription = formatVideoDuration(frame.timeMs),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(shape)
                                                .then(
                                                    if (selected) {
                                                        Modifier.border(
                                                            width = 3.dp,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            shape = shape,
                                                        )
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                        )
                                    } else {
                                        StoryboardPlaceholder(
                                            complete = current.complete,
                                            shape = shape,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    Text(
                                        text = formatVideoDuration(frame.timeMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White
                                        },
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .background(
                                                Color.Black.copy(alpha = 0.62f),
                                                RoundedCornerShape(topEnd = 7.dp),
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }

                        if (!current.complete) {
                            item(key = "storyboard-loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadingIndicator(Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                } else {
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalAlignment = Alignment.Top,
                    ) {
                        items(frames, key = { it.index }) { frame ->
                            val image = frame.file?.takeIf { it.isFile && it.length() > 0L }
                            val selected = frame.index == nearestIndex
                            val shape = RoundedCornerShape(8.dp)
                            Column(
                                modifier = Modifier
                                    .width(120.dp)
                                    .combinedClickable(
                                        enabled = image != null,
                                        onClick = { onSeek(frame.timeMs) },
                                        onLongClick = { fineFrameIndex = frame.index },
                                    ),
                            ) {
                                if (image != null) {
                                    AsyncImage(
                                        model = image,
                                        contentDescription = formatVideoDuration(frame.timeMs),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .clip(shape)
                                            .then(
                                                if (selected) {
                                                    Modifier.border(
                                                        width = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = shape,
                                                    )
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    )
                                } else {
                                    StoryboardPlaceholder(
                                        complete = current.complete,
                                        shape = shape,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f),
                                    )
                                }
                                StoryboardTimestamp(frame.timeMs, selected)
                            }
                        }

                        if (!current.complete) {
                            item(key = "storyboard-loading") {
                                Box(
                                    modifier = Modifier
                                        .width(52.dp)
                                        .height(68.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadingIndicator(Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                }

                if (showJumpToCurrent) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = nearestIndex >= 0,
                            onClick = {
                                if (nearestIndex >= 0) {
                                    scope.launch {
                                        listState.animateScrollToItem(nearestIndex)
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.cast_jump_to_current_storyboard),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }

            fineFrameIndex?.let { index ->
                frames.getOrNull(index)?.let { frame ->
                    StoryboardFinePreviewDialog(
                        entry = entry,
                        centerTimeMs = frame.timeMs,
                        stepMs = storyboardFineStepMs(frames, index),
                        durationMs = current.result.durationMs,
                        onDismiss = { fineFrameIndex = null },
                        onSelect = { timeMs ->
                            fineFrameIndex = null
                            onSeek(timeMs)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StoryboardPlaceholder(
    complete: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!complete) {
            LoadingIndicator(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun StoryboardTimestamp(timeMs: Long, selected: Boolean) {
    Text(
        text = formatVideoDuration(timeMs),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            Color.White.copy(alpha = 0.72f)
        },
        modifier = Modifier.padding(top = 5.dp, start = 2.dp),
    )
}

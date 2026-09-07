package app.local1st.files.ui.viewer

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
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
import app.local1st.files.ui.browser.StoryboardLoader
import app.local1st.files.ui.browser.StoryboardResult
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
 * Vertically scrollable storyboard for the remote Cast controller.
 *
 * It reuses the browser storyboard loader and its disk cache, so opening a storyboard from the
 * browser and then casting the same file does not decode the same frames twice. Generation is
 * progressive: the first visible frames appear while the remaining samples are still extracted.
 */
@Composable
internal fun CastStoryboardStrip(
    entry: XEntry,
    positionMs: Long,
    onSeek: (Long) -> Unit,
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
            ) { mutableStateOf(false) }

            LaunchedEffect(frames.size, nearestIndex, current.complete) {
                if (alignedToPlayback || nearestIndex < 0) return@LaunchedEffect
                if (positionMs <= 0L && !current.complete) return@LaunchedEffect
                if (frames.none { it.file?.isFile == true && it.file.length() > 0L }) {
                    return@LaunchedEffect
                }
                listState.scrollToItem(nearestIndex)
                alignedToPlayback = true
            }

            Column(modifier = modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
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

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(frames, key = { it.index }) { frame ->
                        val image = frame.file?.takeIf { it.isFile && it.length() > 0L }
                        val selected = frame.index == nearestIndex
                        val shape = RoundedCornerShape(12.dp)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = image != null) { onSeek(frame.timeMs) },
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
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(shape)
                                        .background(Color.White.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (!current.complete) {
                                        LoadingIndicator(Modifier.size(28.dp))
                                    }
                                }
                            }
                            Text(
                                text = formatVideoDuration(frame.timeMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.72f)
                                },
                                modifier = Modifier.padding(top = 5.dp, start = 2.dp),
                            )
                        }
                    }

                    if (!current.complete) {
                        item(key = "storyboard-loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingIndicator(Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

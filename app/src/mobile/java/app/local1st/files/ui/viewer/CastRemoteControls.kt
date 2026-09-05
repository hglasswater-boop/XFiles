package app.local1st.files.ui.viewer

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.local1st.files.core.fs.XEntry
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun CastRemoteControls(
    player: Player,
    entry: XEntry,
    playing: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onClose: () -> Unit,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var userScrubbing by remember { mutableStateOf(false) }
    var pendingSeekTargetMs by remember { mutableStateOf<Long?>(null) }
    var inFlightSeekTargetMs by remember { mutableStateOf<Long?>(null) }
    var inFlightSeekDeadlineMs by remember { mutableLongStateOf(0L) }
    var lastSubmittedSeekAtMs by remember { mutableLongStateOf(0L) }

    fun boundedSeekTarget(targetMs: Long): Long {
        val nonNegative = targetMs.coerceAtLeast(0L)
        return if (durationMs > 0L) nonNegative.coerceAtMost(durationMs) else nonNegative
    }

    fun submitSeek(targetMs: Long, coalesceBurst: Boolean) {
        val target = boundedSeekTarget(targetMs)
        positionMs = target

        val now = SystemClock.elapsedRealtime()
        val insideBurstWindow = lastSubmittedSeekAtMs > 0L &&
            now - lastSubmittedSeekAtMs < CAST_SEEK_COALESCE_WINDOW_MS
        if (coalesceBurst && (pendingSeekTargetMs != null || insideBurstWindow)) {
            pendingSeekTargetMs = target
            return
        }

        pendingSeekTargetMs = null
        player.seekTo(target)
        lastSubmittedSeekAtMs = now
        inFlightSeekTargetMs = target
        inFlightSeekDeadlineMs = now + CAST_SEEK_ACK_TIMEOUT_MS
    }

    fun activeSeekBase(): Long = pendingSeekTargetMs ?: inFlightSeekTargetMs ?: positionMs

    LaunchedEffect(pendingSeekTargetMs, player, entry.id) {
        val target = pendingSeekTargetMs ?: return@LaunchedEffect
        delay(CAST_SEEK_COALESCE_WINDOW_MS)
        if (pendingSeekTargetMs != target) return@LaunchedEffect

        val now = SystemClock.elapsedRealtime()
        player.seekTo(target)
        lastSubmittedSeekAtMs = now
        inFlightSeekTargetMs = target
        inFlightSeekDeadlineMs = now + CAST_SEEK_ACK_TIMEOUT_MS
        pendingSeekTargetMs = null
    }

    LaunchedEffect(player, entry.id) {
        while (isActive) {
            val remotePositionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            val now = SystemClock.elapsedRealtime()
            val inFlightTarget = inFlightSeekTargetMs

            when {
                userScrubbing || pendingSeekTargetMs != null -> Unit
                inFlightTarget != null -> {
                    val acknowledged = abs(remotePositionMs - inFlightTarget) <= CAST_SEEK_ACK_TOLERANCE_MS
                    val timedOut = now >= inFlightSeekDeadlineMs
                    if (acknowledged || timedOut) {
                        inFlightSeekTargetMs = null
                        positionMs = remotePositionMs
                    }
                }
                else -> positionMs = remotePositionMs
            }
            delay(CAST_POSITION_REFRESH_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                "Chromecast",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                MediaRouteButton()
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
        ) {
            Text(
                entry.name,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { player.seekToPreviousMediaItem() },
                    enabled = hasPrevious,
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        submitSeek(activeSeekBase() - CAST_SEEK_STEP_MS, coalesceBurst = true)
                    },
                ) {
                    Icon(Icons.Outlined.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
                }
                FilledIconButton(onClick = { if (playing) player.pause() else player.play() }) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
                IconButton(
                    onClick = {
                        submitSeek(activeSeekBase() + CAST_SEEK_STEP_MS, coalesceBurst = true)
                    },
                ) {
                    Icon(Icons.Outlined.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
                }
                IconButton(
                    onClick = { player.seekToNextMediaItem() },
                    enabled = hasNext,
                ) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Next", tint = Color.White)
                }
            }

            if (durationMs > 0L) {
                Slider(
                    value = positionMs.coerceAtMost(durationMs).toFloat(),
                    onValueChange = {
                        userScrubbing = true
                        positionMs = it.toLong()
                    },
                    onValueChangeFinished = {
                        userScrubbing = false
                        submitSeek(positionMs, coalesceBurst = false)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatCastTime(positionMs), color = Color.White)
                    Text(formatCastTime(durationMs), color = Color.White)
                }
            }
        }
    }
}

private fun formatCastTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private const val CAST_SEEK_STEP_MS = 10_000L
private const val CAST_SEEK_COALESCE_WINDOW_MS = 400L
private const val CAST_SEEK_ACK_TIMEOUT_MS = 3_000L
private const val CAST_SEEK_ACK_TOLERANCE_MS = 1_500L
private const val CAST_POSITION_REFRESH_INTERVAL_MS = 200L

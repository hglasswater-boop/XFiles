package app.local1st.files.ui.viewer

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
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
    var seekBurstDeltaMs by remember(entry.id) { mutableLongStateOf(0L) }
    var lastSeekTapAtMs by remember(entry.id) { mutableLongStateOf(0L) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    fun recordSeekStep(deltaMs: Long) {
        val now = SystemClock.elapsedRealtime()
        seekBurstDeltaMs = if (
            lastSeekTapAtMs > 0L && now - lastSeekTapAtMs <= CAST_SEEK_OVERLAY_ACCUMULATE_MS
        ) {
            seekBurstDeltaMs + deltaMs
        } else {
            deltaMs
        }
        lastSeekTapAtMs = now
    }

    LaunchedEffect(lastSeekTapAtMs, entry.id) {
        val tapAt = lastSeekTapAtMs
        if (tapAt == 0L) return@LaunchedEffect
        delay(CAST_SEEK_OVERLAY_VISIBLE_MS)
        if (lastSeekTapAtMs == tapAt) seekBurstDeltaMs = 0L
    }

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

    val sliderTarget = positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat()
    val animatedSliderPosition by animateFloatAsState(
        targetValue = sliderTarget,
        animationSpec = tween(durationMillis = CAST_SLIDER_ANIMATION_MS),
        label = "castPosition",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
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

        AnimatedVisibility(
            visible = seekBurstDeltaMs != 0L,
            enter = fadeIn(tween(90)) + scaleIn(
                animationSpec = tween(120),
                initialScale = 0.88f,
            ),
            exit = fadeOut(tween(180)) + scaleOut(
                animationSpec = tween(180),
                targetScale = 0.96f,
            ),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
            ) {
                Text(
                    text = formatCastSeekDelta(seekBurstDeltaMs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    top = if (isLandscape) 56.dp else 68.dp,
                    bottom = if (isLandscape) 10.dp else 18.dp,
                ),
        ) {
            Text(
                entry.name,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = if (isLandscape) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 4.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                IconButton(
                    onClick = { player.seekToPreviousMediaItem() },
                    enabled = hasPrevious,
                ) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        recordSeekStep(-CAST_SEEK_STEP_MS)
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
                        recordSeekStep(CAST_SEEK_STEP_MS)
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

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(
                        horizontal = 12.dp,
                        top = if (isLandscape) 0.dp else 4.dp,
                        bottom = if (isLandscape) 2.dp else 8.dp,
                    ),
            ) {
                CastStoryboardStrip(
                    entry = entry,
                    positionMs = positionMs,
                    onSeek = { targetMs ->
                        userScrubbing = false
                        submitSeek(targetMs, coalesceBurst = false)
                    },
                    vertical = !isLandscape,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (durationMs > 0L) {
                Slider(
                    value = if (userScrubbing) sliderTarget else animatedSliderPosition,
                    onValueChange = {
                        userScrubbing = true
                        positionMs = it.toLong()
                    },
                    onValueChangeFinished = {
                        userScrubbing = false
                        submitSeek(positionMs, coalesceBurst = false)
                    },
                    valueRange = 0f..durationMs.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
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

private fun formatCastSeekDelta(deltaMs: Long): String {
    val seconds = abs(deltaMs / 1000L)
    return if (deltaMs > 0L) "+${seconds}秒" else "−${seconds}秒"
}

private const val CAST_SEEK_STEP_MS = 10_000L
private const val CAST_SEEK_COALESCE_WINDOW_MS = 400L
private const val CAST_SEEK_ACK_TIMEOUT_MS = 3_000L
private const val CAST_SEEK_ACK_TOLERANCE_MS = 1_500L
private const val CAST_POSITION_REFRESH_INTERVAL_MS = 100L
private const val CAST_SEEK_OVERLAY_ACCUMULATE_MS = 850L
private const val CAST_SEEK_OVERLAY_VISIBLE_MS = 700L
private const val CAST_SLIDER_ANIMATION_MS = 110

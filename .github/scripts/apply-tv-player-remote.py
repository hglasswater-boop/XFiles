from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} anchor not found")
    return text.replace(old, new, 1)


# Shared video player: add an opt-in remote-first TV mode while keeping mobile unchanged.
video_path = Path("app/src/main/java/app/local1st/files/ui/viewer/VideoPlayer.kt")
video = video_path.read_text()

video = replace_once(
    video,
    '''import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
''',
    '''import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
''',
    "video activity import",
)
video = replace_once(
    video,
    '''import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
''',
    '''import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
''',
    "video focusable import",
)
video = replace_once(
    video,
    '''import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
''',
    '''import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
''',
    "video key imports",
)
video = replace_once(
    video,
    '''    hasPrevious: Boolean,
    hasNext: Boolean,
    onClose: () -> Unit,
) {
''',
    '''    hasPrevious: Boolean,
    hasNext: Boolean,
    onClose: () -> Unit,
    tvRemoteControls: Boolean = false,
) {
''',
    "video function signature",
)
video = replace_once(
    video,
    '''    var cardDragging by remember { mutableStateOf(false) }
    var showPlayerSettings by remember { mutableStateOf(false) }

    // Orientation and PiP belong to the player lifetime, not the auto-hidden controls panel.
    val orientationController = rememberVideoOrientationController()
''',
    '''    var cardDragging by remember { mutableStateOf(false) }
    var showPlayerSettings by remember { mutableStateOf(false) }
    val tvFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = tvRemoteControls && controlsVisible) {
        controlsVisible = false
        showPlayerSettings = false
        interactionTick++
    }

    LaunchedEffect(tvRemoteControls, entry.id) {
        if (!tvRemoteControls) return@LaunchedEffect
        repeat(TV_FOCUS_RETRY_FRAMES) {
            withFrameNanos { }
            val focused = runCatching { tvFocusRequester.requestFocus() }.getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
    }

    // Phone/tablet playback can follow or lock device orientation. A TV already has a fixed
    // display orientation, so remote mode deliberately avoids requesting any orientation change.
    val orientationController = if (tvRemoteControls) null else rememberVideoOrientationController()
''',
    "video TV state",
)
video = replace_once(
    video,
    '''    fun stepSeconds(delta: Int) {
        seekGate.request(clampMs(anchorMs() + delta * 1000L))
        interactionTick++
    }

    LaunchedEffect(playing) {
''',
    '''    fun stepSeconds(delta: Int) {
        seekGate.request(clampMs(anchorMs() + delta * 1000L))
        interactionTick++
    }
    fun togglePlayback() {
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_ENDED -> {
                player.seekToDefaultPosition()
                player.play()
            }
            else -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
        interactionTick++
    }

    LaunchedEffect(playing) {
''',
    "video playback helper",
)
video = replace_once(
    video,
    '''    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { parentHeightPx = it.height },
    ) {
''',
    '''    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { parentHeightPx = it.height }
            .then(
                if (tvRemoteControls) {
                    Modifier
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> {
                                        togglePlayback()
                                        controlsVisible = true
                                        true
                                    }
                                    Key.DirectionLeft -> {
                                        stepSeconds(-TV_REMOTE_SEEK_SECONDS)
                                        tapSeekLabel = "-${TV_REMOTE_SEEK_SECONDS}秒"
                                        controlsVisible = true
                                        true
                                    }
                                    Key.DirectionRight -> {
                                        stepSeconds(TV_REMOTE_SEEK_SECONDS)
                                        tapSeekLabel = "+${TV_REMOTE_SEEK_SECONDS}秒"
                                        controlsVisible = true
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        controlsVisible = true
                                        interactionTick++
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        controlsVisible = false
                                        showPlayerSettings = false
                                        interactionTick++
                                        true
                                    }
                                    else -> false
                                }
                            }
                        }
                        .focusRequester(tvFocusRequester)
                        .focusable()
                } else {
                    Modifier
                },
            ),
    ) {
''',
    "video root remote keys",
)
video = replace_once(
    video,
    '''            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
                Text(
''',
    '''            ) {
                if (!tvRemoteControls) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                    }
                }
                Text(
''',
    "video close button",
)
video = replace_once(
    video,
    '''                Box {
                    IconButton(onClick = { showPlayerSettings = true }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "プレイヤー設定",
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = showPlayerSettings,
                        onDismissRequest = { showPlayerSettings = false },
                    ) {
''',
    '''                if (!tvRemoteControls) {
                    Box {
                        IconButton(onClick = { showPlayerSettings = true }) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "プレイヤー設定",
                                tint = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = showPlayerSettings,
                            onDismissRequest = { showPlayerSettings = false },
                        ) {
''',
    "video settings open",
)
video = replace_once(
    video,
    '''                        Slider(
                            value = controlsTransparencyPercent.toFloat(),
                            onValueChange = { value ->
                                VideoPlayerSettings.setControlsTransparencyPercent(
                                    context,
                                    (value / 5f).roundToInt() * 5,
                                )
                                interactionTick++
                            },
                            valueRange = 0f..60f,
                            steps = 11,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        }
''',
    '''                            Slider(
                                value = controlsTransparencyPercent.toFloat(),
                                onValueChange = { value ->
                                    VideoPlayerSettings.setControlsTransparencyPercent(
                                        context,
                                        (value / 5f).roundToInt() * 5,
                                    )
                                    interactionTick++
                                },
                                valueRange = 0f..60f,
                                steps = 11,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        }
''',
    "video settings close",
)
video = replace_once(
    video,
    '''                            FilledIconButton(
                                onClick = {
                                    when {
                                        playing -> player.pause()
                                        player.playbackState == Player.STATE_ENDED -> {
                                            player.seekToDefaultPosition()
                                            player.play()
                                        }
                                        else -> {
                                            if (player.playbackState == Player.STATE_IDLE) {
                                                player.prepare()
                                            }
                                            player.play()
                                        }
                                    }
                                    interactionTick++
                                },
                                modifier = Modifier.size(44.dp),
                            ) {
''',
    '''                            FilledIconButton(
                                onClick = { togglePlayback() },
                                modifier = Modifier.size(44.dp),
                            ) {
''',
    "video play button",
)
video = replace_once(
    video,
    '''                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            VideoOrientationQuickControls(orientationController) {
                                interactionTick++
                            }
                        }
''',
    '''                        if (orientationController != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.End,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            ) {
                                VideoOrientationQuickControls(orientationController) {
                                    interactionTick++
                                }
                            }
                        }
''',
    "video orientation controls",
)
video = replace_once(
    video,
    '''private const val AUTO_HIDE_MS = 4000L
private const val STEP_SECONDS = 5
''',
    '''private const val AUTO_HIDE_MS = 4000L
private const val STEP_SECONDS = 5
private const val TV_REMOTE_SEEK_SECONDS = 10
private const val TV_FOCUS_RETRY_FRAMES = 8
''',
    "video TV constants",
)
video_path.write_text(video)


# TV media host: let the shared player own D-pad handling so it can also control its chrome.
tv_path = Path("app/src/tv/java/app/local1st/files/ui/viewer/MediaViewer.kt")
tv = tv_path.read_text()
for line in [
    "import androidx.compose.foundation.focusable\n",
    "import androidx.compose.runtime.withFrameNanos\n",
    "import androidx.compose.ui.focus.FocusRequester\n",
    "import androidx.compose.ui.focus.focusRequester\n",
    "import androidx.compose.ui.input.key.Key\n",
    "import androidx.compose.ui.input.key.KeyEventType\n",
    "import androidx.compose.ui.input.key.key\n",
    "import androidx.compose.ui.input.key.onPreviewKeyEvent\n",
    "import androidx.compose.ui.input.key.type\n",
]:
    tv = tv.replace(line, "")

tv = replace_once(
    tv,
    '''    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    val remoteFocusRequester = remember { FocusRequester() }

    BackHandler { onClose() }

    LaunchedEffect(player, currentIndex) {
        repeat(8) {
            withFrameNanos { }
            val focused = runCatching { remoteFocusRequester.requestFocus() }.getOrDefault(false)
            if (focused) return@LaunchedEffect
        }
    }

    fun seekByRemote(deltaMs: Long) {
        val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = player.currentPosition + deltaMs
        player.seekTo(
            if (durationMs != null) target.coerceIn(0L, durationMs)
            else target.coerceAtLeast(0L),
        )
    }

    DisposableEffect(player) {
''',
    '''    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    DisposableEffect(player) {
''',
    "TV host remote state",
)

tv = replace_once(
    tv,
    '''    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (player.isPlaying) player.pause() else player.play()
                            true
                        }
                        Key.DirectionLeft -> {
                            seekByRemote(-TV_REMOTE_SEEK_MS)
                            true
                        }
                        Key.DirectionRight -> {
                            seekByRemote(TV_REMOTE_SEEK_MS)
                            true
                        }
                        // Keep focus inside the player. The TV player is operated directly by
                        // the remote instead of navigating the phone-oriented touch controls.
                        Key.DirectionUp, Key.DirectionDown -> true
                        else -> false
                    }
                }
            }
            .focusRequester(remoteFocusRequester)
            .focusable(),
    ) {
        if (isVideo) {
            VideoCompatibilityGuard(
                player = player,
                entry = currentEntry,
                onClose = onClose,
            ) {
                VideoPlayerScreen(
                    player = player,
                    entry = currentEntry,
                    playing = playing,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    onClose = onClose,
                )
            }
        } else {
            AudioPlayerScreen(
                player = player,
                entry = currentEntry,
                metadata = metadata,
                playing = playing,
                trackNumber = currentIndex + 1,
                trackCount = playable.size,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onClose = onClose,
            )
        }
    }
''',
    '''    if (isVideo) {
        VideoCompatibilityGuard(
            player = player,
            entry = currentEntry,
            onClose = onClose,
        ) {
            VideoPlayerScreen(
                player = player,
                entry = currentEntry,
                playing = playing,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onClose = onClose,
                tvRemoteControls = true,
            )
        }
    } else {
        AudioPlayerScreen(
            player = player,
            entry = currentEntry,
            metadata = metadata,
            playing = playing,
            trackNumber = currentIndex + 1,
            trackCount = playable.size,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onClose = onClose,
        )
    }
''',
    "TV host render",
)

tv = tv.replace("private const val TV_REMOTE_SEEK_MS = 10_000L\n", "")
tv_path.write_text(tv)

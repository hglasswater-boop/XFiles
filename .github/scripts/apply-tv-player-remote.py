from pathlib import Path

path = Path("app/src/tv/java/app/local1st/files/ui/viewer/MediaViewer.kt")
text = path.read_text()

imports_anchor = '''import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
'''
imports_replacement = '''import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
'''
if imports_anchor not in text:
    raise SystemExit("imports anchor not found")
text = text.replace(imports_anchor, imports_replacement, 1)

runtime_anchor = '''import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
'''
runtime_replacement = '''import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
'''
if runtime_anchor not in text:
    raise SystemExit("runtime imports anchor not found")
text = text.replace(runtime_anchor, runtime_replacement, 1)

state_anchor = '''    var currentIndex by remember { mutableIntStateOf(startIndex) }
    var playing by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
'''
state_replacement = '''    var currentIndex by remember { mutableIntStateOf(startIndex) }
    var playing by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var hasPrevious by remember { mutableStateOf(false) }
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
'''
if state_anchor not in text:
    raise SystemExit("player state anchor not found")
text = text.replace(state_anchor, state_replacement, 1)

render_anchor = '''    if (isVideo) {
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
'''
render_replacement = '''    Box(
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
'''
if render_anchor not in text:
    raise SystemExit("player render anchor not found")
text = text.replace(render_anchor, render_replacement, 1)

const_anchor = '''private const val VIDEO_RESUME_SAVE_INTERVAL_MS = 2_000L
private const val VIDEO_RESUME_RESTORE_TOLERANCE_MS = 2_000L
'''
const_replacement = '''private const val VIDEO_RESUME_SAVE_INTERVAL_MS = 2_000L
private const val VIDEO_RESUME_RESTORE_TOLERANCE_MS = 2_000L
private const val TV_REMOTE_SEEK_MS = 10_000L
'''
if const_anchor not in text:
    raise SystemExit("constant anchor not found")
text = text.replace(const_anchor, const_replacement, 1)

path.write_text(text)

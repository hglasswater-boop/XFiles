package app.local1st.files.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.prefs.VIDEO_AV_SYNC_MAX_OFFSET_MS
import app.local1st.files.core.prefs.VideoAvSyncStore
import app.local1st.files.ui.components.TooltipIconButton
import kotlin.math.abs

/** Manual, per-video A/V sync control. Automatic estimation is layered on this same offset path. */
@Composable
internal fun VideoAvSyncButton(
    player: ExoPlayer,
    entry: XEntry,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showDialog by remember(entry.id) { mutableStateOf(false) }
    var offsetMs by remember(entry.id) {
        mutableLongStateOf(VideoAvSyncStore.load(context, entry.id))
    }

    fun applyOffset(requestedMs: Long) {
        val normalized = requestedMs.coerceIn(
            -VIDEO_AV_SYNC_MAX_OFFSET_MS,
            VIDEO_AV_SYNC_MAX_OFFSET_MS,
        )
        offsetMs = normalized
        VideoAvSyncStore.save(context, entry.id, normalized)
        if (PlaybackAudioOffset.controller.setOffsetMs(normalized) &&
            player.currentMediaItem?.mediaId == entry.id
        ) {
            // A same-position seek flushes AudioSink/decoder state and makes the new correction
            // start at a clean playback window without changing play/pause state.
            player.seekTo(player.currentPosition.coerceAtLeast(0L))
        }
    }

    LaunchedEffect(player, entry.id) {
        val saved = VideoAvSyncStore.load(context, entry.id)
        offsetMs = saved
        if (PlaybackAudioOffset.controller.setOffsetMs(saved) &&
            player.currentMediaItem?.mediaId == entry.id
        ) {
            player.seekTo(player.currentPosition.coerceAtLeast(0L))
        }
    }

    DisposableEffect(player, entry.id) {
        onDispose {
            // Do not erase the next video's value during an in-player media transition. Reset only
            // when this item is still active (for example when the viewer itself is closed).
            if (player.currentMediaItem?.mediaId == entry.id) {
                PlaybackAudioOffset.controller.setOffsetMs(0L)
            }
        }
    }

    TooltipIconButton(
        label = avSyncTooltip(offsetMs),
        icon = Icons.Outlined.Sync,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("音ズレ補正") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(avSyncDescription(offsetMs))
                    Text("口より声が遅い場合は「音声を前へ」、声が早い場合は「音声を後ろへ」で合わせます。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = { applyOffset(offsetMs - 100L) }) {
                            Text("前へ100")
                        }
                        TextButton(onClick = { applyOffset(offsetMs - 50L) }) {
                            Text("前へ50")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = { applyOffset(offsetMs + 50L) }) {
                            Text("後ろへ50")
                        }
                        TextButton(onClick = { applyOffset(offsetMs + 100L) }) {
                            Text("後ろへ100")
                        }
                    }
                    Text("この値は動画ごとに保存されます。自動解析はこの補正値を安全に提案する機能として追加します。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("閉じる")
                }
            },
            dismissButton = {
                TextButton(onClick = { applyOffset(0L) }) {
                    Text("リセット")
                }
            },
        )
    }
}

internal fun avSyncDescription(offsetMs: Long): String = when {
    offsetMs < 0L -> "現在: 音声を ${abs(offsetMs)} ms 前へ"
    offsetMs > 0L -> "現在: 音声を ${offsetMs} ms 後ろへ"
    else -> "現在: 補正なし (0 ms)"
}

private fun avSyncTooltip(offsetMs: Long): String = when {
    offsetMs < 0L -> "音ズレ補正: 音声 ${abs(offsetMs)}ms 前へ"
    offsetMs > 0L -> "音ズレ補正: 音声 ${offsetMs}ms 後ろへ"
    else -> "音ズレ補正"
}

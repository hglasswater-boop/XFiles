package app.local1st.files.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.prefs.VIDEO_AV_SYNC_MAX_OFFSET_MS
import app.local1st.files.core.prefs.VideoAvSyncStore
import app.local1st.files.ui.components.TooltipIconButton
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Per-video manual A/V sync control with an offline lip/speech estimate for local files. */
@Composable
internal fun VideoAvSyncButton(
    player: ExoPlayer,
    entry: XEntry,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember(entry.id) { mutableStateOf(false) }
    var offsetMs by remember(entry.id) {
        mutableLongStateOf(VideoAvSyncStore.load(context, entry.id))
    }
    var analyzing by remember(entry.id) { mutableStateOf(false) }
    var analysisResult by remember(entry.id) {
        mutableStateOf<LocalLipSyncAnalysisResult?>(null)
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

    fun startAutomaticAnalysis() {
        if (analyzing || entry.localPath == null) return
        val analysisPositionMs = player.currentPosition.coerceAtLeast(0L)
        val resumeAfterAnalysis = player.isPlaying
        player.pause()
        analysisResult = null
        analyzing = true
        scope.launch {
            try {
                analysisResult = LocalLipSyncAnalyzer.analyze(
                    localPath = entry.localPath,
                    centerPositionMs = analysisPositionMs,
                )
            } finally {
                analyzing = false
                if (resumeAfterAnalysis && player.currentMediaItem?.mediaId == entry.id) {
                    player.play()
                }
            }
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
            onDismissRequest = { if (!analyzing) showDialog = false },
            title = { Text("音ズレ補正") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(avSyncDescription(offsetMs))
                    Text("口より声が遅い場合は「音声を前へ」、声が早い場合は「音声を後ろへ」で合わせます。")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(
                            enabled = !analyzing,
                            onClick = { applyOffset(offsetMs - 100L) },
                        ) {
                            Text("前へ100")
                        }
                        TextButton(
                            enabled = !analyzing,
                            onClick = { applyOffset(offsetMs - 50L) },
                        ) {
                            Text("前へ50")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(
                            enabled = !analyzing,
                            onClick = { applyOffset(offsetMs + 50L) },
                        ) {
                            Text("後ろへ50")
                        }
                        TextButton(
                            enabled = !analyzing,
                            onClick = { applyOffset(offsetMs + 100L) },
                        ) {
                            Text("後ろへ100")
                        }
                    }

                    TextButton(
                        enabled = !analyzing && entry.localPath != null,
                        onClick = ::startAutomaticAnalysis,
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  口と声を解析中…")
                        } else {
                            Text("口と声から自動解析")
                        }
                    }
                    if (entry.localPath == null) {
                        Text("自動解析は現在ローカル動画のみ対応です。手動補正はこのまま利用できます。")
                    }
                    when (val result = analysisResult) {
                        is LocalLipSyncAnalysisResult.Success -> {
                            val analysis = result.analysis
                            val suggested = analysis.estimate.offsetMs
                            val confidencePercent = (analysis.confidence * 100f).roundToInt()
                            Text(
                                "推定: ${avSyncDescription(suggested).removePrefix("現在: ")} " +
                                    "(信頼度 $confidencePercent%)",
                            )
                            if (analysis.confidence >= AUTO_SUGGESTION_MIN_CONFIDENCE) {
                                TextButton(
                                    enabled = !analyzing,
                                    onClick = { applyOffset(suggested) },
                                ) {
                                    Text("推定値を使う")
                                }
                            } else {
                                Text("信頼度が低いため自動適用候補にはしません。話している顔が見える位置で再解析してください。")
                            }
                        }
                        is LocalLipSyncAnalysisResult.Unavailable -> Text(result.reason)
                        null -> Unit
                    }
                    Text("補正値は動画ごとに保存されます。自動解析の推定値も、確認して選んだ場合だけ適用します。")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !analyzing,
                    onClick = { showDialog = false },
                ) {
                    Text("閉じる")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !analyzing,
                    onClick = { applyOffset(0L) },
                ) {
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

private const val AUTO_SUGGESTION_MIN_CONFIDENCE = 0.35f

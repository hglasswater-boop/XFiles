package app.local1st.files.ui.viewer

import android.media.MediaCodecList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.media.VideoMetadataReader
import app.local1st.files.core.util.IntentUtils

private sealed interface VideoCompatibilityState {
    data object Checking : VideoCompatibilityState
    data object Supported : VideoCompatibilityState
    data class Unsupported(val codec: String?) : VideoCompatibilityState
    data class PlaybackFailed(val code: Int, val isFileReadFailure: Boolean) : VideoCompatibilityState
}

/**
 * Keeps Media3 as the only bundled playback engine. Before video playback starts, codecs whose
 * Android MIME type is known are checked against the device's installed decoders. If no decoder is
 * available, or Media3 later reports a decoder failure, XFiles stops playback and offers the system
 * "Open with" chooser instead of leaving the user with audio over a black screen.
 */
@Composable
internal fun VideoCompatibilityGuard(
    player: ExoPlayer,
    entry: XEntry,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var state by remember(entry.id) { mutableStateOf<VideoCompatibilityState>(VideoCompatibilityState.Checking) }
    var externalOpenFailed by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(player, entry.id) {
        val resumeWhenSupported = player.playWhenReady || player.isPlaying
        player.pause()
        val metadata = VideoMetadataReader.read(entry)
        val codec = metadata?.codec
        val mime = mimeForCodecLabel(codec)
        state = if (mime != null && !hasPlatformDecoder(mime)) {
            VideoCompatibilityState.Unsupported(codec ?: mime)
        } else {
            VideoCompatibilityState.Supported
        }
        if (state is VideoCompatibilityState.Supported && resumeWhenSupported) {
            player.play()
        }
    }

    DisposableEffect(player, entry.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                player.pause()
                state = when (classifyVideoPlaybackFailure(error)) {
                    VideoPlaybackFailure.DECODER -> {
                        val codec = player.videoFormat?.sampleMimeType?.let(::codecLabelForMime)
                        VideoCompatibilityState.Unsupported(codec)
                    }
                    VideoPlaybackFailure.FILE_IO -> VideoCompatibilityState.PlaybackFailed(
                        error.errorCode,
                        isFileReadFailure = true,
                    )
                    VideoPlaybackFailure.OTHER -> VideoCompatibilityState.PlaybackFailed(
                        error.errorCode,
                        isFileReadFailure = false,
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    when (val compatibility = state) {
        VideoCompatibilityState.Checking -> Unit
        VideoCompatibilityState.Supported -> content()
        is VideoCompatibilityState.Unsupported -> {
            VideoPlaybackFailureCard(
                title = stringResource(R.string.cannot_play, entry.name),
                detail = compatibility.codec?.let { stringResource(R.string.file_type, it) },
                canOpenExternally = IntentUtils.canExternalRead(entry),
                externalOpenFailed = externalOpenFailed,
                entryName = entry.name,
                onOpenExternally = {
                    externalOpenFailed = !IntentUtils.openWith(context, entry)
                },
                onClose = onClose,
            )
        }
        is VideoCompatibilityState.PlaybackFailed -> {
            VideoPlaybackFailureCard(
                title = stringResource(
                    if (compatibility.isFileReadFailure) R.string.cannot_read else R.string.cannot_play,
                    entry.name,
                ),
                detail = "Media3 error ${compatibility.code}",
                canOpenExternally = IntentUtils.canExternalRead(entry),
                externalOpenFailed = externalOpenFailed,
                entryName = entry.name,
                onOpenExternally = {
                    externalOpenFailed = !IntentUtils.openWith(context, entry)
                },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun VideoPlaybackFailureCard(
    title: String,
    detail: String?,
    entryName: String,
    canOpenExternally: Boolean,
    externalOpenFailed: Boolean,
    onOpenExternally: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ElevatedCard(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (externalOpenFailed) {
                    Text(
                        stringResource(R.string.no_app_can_open, entryName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.close))
                    }
                    if (canOpenExternally) {
                        TextButton(onClick = onOpenExternally) {
                            Text(stringResource(R.string.open_with))
                        }
                    }
                }
            }
        }
    }
}

private fun hasPlatformDecoder(mime: String): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
}.getOrDefault(true)

private fun mimeForCodecLabel(codec: String?): String? = when (codec) {
    "H.264 / AVC" -> "video/avc"
    "H.265 / HEVC" -> "video/hevc"
    "AV1" -> "video/av01"
    "VP9" -> "video/x-vnd.on2.vp9"
    "VP8" -> "video/x-vnd.on2.vp8"
    "MPEG-4 Visual" -> "video/mp4v-es"
    "MPEG-2" -> "video/mpeg2"
    "H.263" -> "video/3gpp"
    else -> null
}

private fun codecLabelForMime(mime: String): String = when (mime.lowercase()) {
    "video/avc" -> "H.264 / AVC"
    "video/hevc" -> "H.265 / HEVC"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4 Visual"
    "video/mpeg2" -> "MPEG-2"
    "video/3gpp" -> "H.263"
    else -> mime
}

internal enum class VideoPlaybackFailure { DECODER, FILE_IO, OTHER }

/** Do not mislabel removable-storage I/O failures as unsupported video codecs. */
internal fun classifyVideoPlaybackFailure(error: PlaybackException): VideoPlaybackFailure = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> VideoPlaybackFailure.DECODER

    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> VideoPlaybackFailure.FILE_IO

    else -> VideoPlaybackFailure.OTHER
}

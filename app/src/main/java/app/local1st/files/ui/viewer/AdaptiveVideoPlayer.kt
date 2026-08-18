package app.local1st.files.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.media.VideoMetadataReader

/**
 * Keeps Media3 as the fast/default path and switches only videos that need a software decoder
 * to LibVLC. MPEG-2 is detected before Media3 gets a chance to produce an audio-only black
 * screen; other uncommon codecs fall back when Media3 reports a decoder failure.
 */
@Composable
internal fun AdaptiveVideoPlayerScreen(
    player: ExoPlayer,
    entry: XEntry,
    playing: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onClose: () -> Unit,
) {
    var softwareMode by remember(entry.id) { mutableStateOf<Boolean?>(null) }
    var resumeMedia3AfterProbe by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(entry.id) {
        resumeMedia3AfterProbe = player.playWhenReady || player.isPlaying
        player.pause()
        val metadata = VideoMetadataReader.read(entry)
        softwareMode = shouldPreferSoftwareDecoder(metadata?.codec) && supportsSoftwarePlayback(entry)
        if (softwareMode == false && resumeMedia3AfterProbe) player.play()
    }

    DisposableEffect(player, entry.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (
                    softwareMode != true &&
                    supportsSoftwarePlayback(entry) &&
                    error.isVideoDecoderFailure()
                ) {
                    softwareMode = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(softwareMode) {
        if (softwareMode == true) player.pause()
    }

    when (softwareMode) {
        true -> SoftwareVideoPlayerScreen(
            entry = entry,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPrevious = {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPreviousMediaItem()
                    player.play()
                }
            },
            onNext = {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.play()
                }
            },
            onClose = onClose,
        )
        false -> VideoPlayerScreen(
            player = player,
            entry = entry,
            playing = playing,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onClose = onClose,
        )
        null -> Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

private fun shouldPreferSoftwareDecoder(codec: String?): Boolean = when (codec) {
    "MPEG-2" -> true
    else -> false
}

internal fun supportsSoftwarePlayback(entry: XEntry): Boolean =
    entry.localPath != null || entry.scheme == XId.SCHEME_ROOT || entry.scheme == "content"

private fun PlaybackException.isVideoDecoderFailure(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
)

package app.local1st.files.ui.viewer

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward5
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay5
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.prefs.VideoResumeStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Compatibility player used only when Android's MediaCodec path cannot decode the video.
 * Hardware decoding is explicitly disabled so uncommon formats such as MPEG-2 are handled by
 * VLC's bundled software decoders instead of falling back onto the same failing device codec.
 */
@Composable
internal fun SoftwareVideoPlayerScreen(
    entry: XEntry,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val libVlc = remember(entry.id) {
        LibVLC(context, arrayListOf("--no-video-title-show", "--quiet"))
    }
    val mediaPlayer = remember(entry.id) { MediaPlayer(libVlc) }
    val descriptorHolder = remember(entry.id) { arrayOfNulls<ParcelFileDescriptor>(1) }

    var playing by remember(entry.id) { mutableStateOf(false) }
    var positionMs by remember(entry.id) { mutableLongStateOf(0L) }
    var durationMs by remember(entry.id) { mutableLongStateOf(0L) }
    var sliderPosition by remember(entry.id) { mutableStateOf<Float?>(null) }
    var loadFailed by remember(entry.id) { mutableStateOf(false) }

    DisposableEffect(mediaPlayer, entry.id) {
        onDispose {
            val duration = runCatching { mediaPlayer.length }.getOrDefault(0L).coerceAtLeast(0L)
            val position = runCatching { mediaPlayer.time }.getOrDefault(0L).coerceAtLeast(0L)
            VideoResumeStore.save(context, entry.id, position, duration)
            runCatching { mediaPlayer.stop() }
            runCatching { mediaPlayer.detachViews() }
            runCatching { descriptorHolder[0]?.close() }
            descriptorHolder[0] = null
            runCatching { mediaPlayer.release() }
            runCatching { libVlc.release() }
        }
    }

    LaunchedEffect(mediaPlayer, entry.id) {
        val opened = openSoftwareMedia(context, libVlc, entry)
        if (opened == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        descriptorHolder[0] = opened.descriptor
        opened.media.setHWDecoderEnabled(false, false)
        mediaPlayer.media = opened.media
        opened.media.release()
        mediaPlayer.play()

        val resumeMs = VideoResumeStore.load(context, entry.id)
        var resumeRestored = resumeMs <= 0L
        while (isActive) {
            val length = runCatching { mediaPlayer.length }.getOrDefault(0L).coerceAtLeast(0L)
            if (!resumeRestored && length > 0L) {
                runCatching { mediaPlayer.time = resumeMs.coerceAtMost((length - 1L).coerceAtLeast(0L)) }
                resumeRestored = true
            }
            positionMs = runCatching { mediaPlayer.time }.getOrDefault(0L).coerceAtLeast(0L)
            durationMs = length
            playing = runCatching { mediaPlayer.isPlaying }.getOrDefault(false)
            delay(100L)
        }
    }

    LaunchedEffect(mediaPlayer, entry.id) {
        while (isActive) {
            delay(SOFTWARE_RESUME_SAVE_INTERVAL_MS)
            val length = runCatching { mediaPlayer.length }.getOrDefault(0L).coerceAtLeast(0L)
            val position = runCatching { mediaPlayer.time }.getOrDefault(0L).coerceAtLeast(0L)
            VideoResumeStore.save(context, entry.id, position, length)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!loadFailed) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        mediaPlayer.attachViews(layout, null, true, false)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                stringResource(R.string.cannot_play, entry.name),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }

        FilledIconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close))
        }

        Surface(
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Slider(
                    value = sliderPosition ?: if (durationMs > 0L) {
                        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = {
                        val fraction = sliderPosition ?: return@Slider
                        if (durationMs > 0L) {
                            mediaPlayer.time = (durationMs * fraction).toLong().coerceIn(0L, durationMs)
                        }
                        sliderPosition = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatPlayTime(positionMs), style = MaterialTheme.typography.labelMedium)
                    Text(formatPlayTime(durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(onClick = onPrevious, enabled = hasPrevious) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = stringResource(R.string.previous))
                    }
                    IconButton(onClick = {
                        mediaPlayer.time = (mediaPlayer.time - 5_000L).coerceAtLeast(0L)
                    }) {
                        Icon(Icons.Outlined.Replay5, contentDescription = null)
                    }
                    FilledIconButton(
                        onClick = { if (playing) mediaPlayer.pause() else mediaPlayer.play() },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                        )
                    }
                    IconButton(onClick = {
                        val max = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                        mediaPlayer.time = (mediaPlayer.time + 5_000L).coerceAtMost(max)
                    }) {
                        Icon(Icons.Outlined.Forward5, contentDescription = null)
                    }
                    IconButton(onClick = onNext, enabled = hasNext) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = stringResource(R.string.next))
                    }
                }
            }
        }
    }
}

private data class OpenedSoftwareMedia(
    val media: Media,
    val descriptor: ParcelFileDescriptor?,
)

private fun openSoftwareMedia(
    context: android.content.Context,
    libVlc: LibVLC,
    entry: XEntry,
): OpenedSoftwareMedia? = runCatching {
    when {
        entry.localPath != null -> OpenedSoftwareMedia(Media(libVlc, entry.localPath), null)
        entry.scheme == XId.SCHEME_ROOT -> {
            val descriptor = PrivilegedAccess.fdTransport()?.openFd(entry.path, write = false)
                ?: return@runCatching null
            OpenedSoftwareMedia(Media(libVlc, descriptor.fileDescriptor), descriptor)
        }
        entry.scheme == "content" -> {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(entry.id), "r")
                ?: return@runCatching null
            OpenedSoftwareMedia(Media(libVlc, descriptor.fileDescriptor), descriptor)
        }
        else -> null
    }
}.getOrNull()

private const val SOFTWARE_RESUME_SAVE_INTERVAL_MS = 2_000L

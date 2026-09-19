package app.local1st.files.debug

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Isolated libVLC A/B harness for Issue #124.
 *
 * This exists only in mobileDebug. It deliberately bypasses Media3 so we can verify whether
 * libVLC's complete decode/audio/A-V sync path avoids the periodic ~200 ms video freeze without
 * introducing long-duration drift. If the A/B succeeds, libVLC can then be integrated behind the
 * normal XFiles player UI instead of changing production playback during diagnosis.
 */
class LibVlcDiagnosticActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LibVlcDiagnosticScreen()
            }
        }
    }
}

@Composable
private fun LibVlcDiagnosticScreen() {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
    }

    val uri = selectedUri
    if (uri == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("libVLC 3.7.6 A/B")
                Text("Issue #124の再現動画を選択")
                Button(onClick = { picker.launch(arrayOf("video/*")) }) {
                    Text("動画を選ぶ")
                }
            }
        }
        return
    }

    LibVlcPlayback(
        uri = uri,
        onPickAnother = { picker.launch(arrayOf("video/*")) },
    )
}

@Composable
private fun LibVlcPlayback(
    uri: Uri,
    onPickAnother: () -> Unit,
) {
    val context = LocalContext.current
    val sessionResult = remember(uri) {
        runCatching { LibVlcSession(context, uri) }
    }
    val session = sessionResult.getOrNull()
    if (session == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("動画を開けませんでした")
                Text(sessionResult.exceptionOrNull()?.message.orEmpty())
                Button(onClick = onPickAnother) { Text("別の動画を選ぶ") }
            }
        }
        return
    }

    var positionMs by remember(session) { mutableLongStateOf(0L) }
    var durationMs by remember(session) { mutableLongStateOf(0L) }
    var playing by remember(session) { mutableStateOf(false) }
    var scrubbing by remember(session) { mutableStateOf(false) }
    var scrubValue by remember(session) { mutableFloatStateOf(0f) }

    DisposableEffect(session) {
        session.start()
        onDispose { session.release() }
    }

    LaunchedEffect(session) {
        while (isActive) {
            positionMs = session.player.time.coerceAtLeast(0L)
            durationMs = session.player.length.coerceAtLeast(0L)
            playing = session.player.isPlaying
            if (!scrubbing && durationMs > 0L) {
                scrubValue = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            }
            delay(100L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).also { layout ->
                    layout.keepScreenOn = true
                    session.player.attachViews(layout, null, false, false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "libVLC 3.7.6 A/B",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Button(onClick = onPickAnother) {
                    Text("別の動画")
                }
            }

            Slider(
                value = scrubValue,
                onValueChange = { value ->
                    scrubbing = true
                    scrubValue = value
                },
                onValueChangeFinished = {
                    if (durationMs > 0L) {
                        session.player.time = (durationMs * scrubValue).toLong()
                    }
                    scrubbing = false
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                    color = Color.White,
                )
                IconButton(
                    onClick = {
                        if (session.player.isPlaying) session.player.pause() else session.player.play()
                    },
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "一時停止" else "再生",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

private class LibVlcSession(
    context: android.content.Context,
    uri: Uri,
) {
    private val appContext = context.applicationContext
    private val descriptor: ParcelFileDescriptor =
        appContext.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("ファイルを開けません")
    private val libVlc = LibVLC(appContext)
    val player = MediaPlayer(libVlc)
    private var started = false

    fun start() {
        if (started) return
        started = true
        val media = Media(libVlc, descriptor.fileDescriptor).apply {
            setHWDecoderEnabled(true, false)
        }
        player.media = media
        media.release()
        player.play()
    }

    fun release() {
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        runCatching { libVlc.release() }
        runCatching { descriptor.close() }
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

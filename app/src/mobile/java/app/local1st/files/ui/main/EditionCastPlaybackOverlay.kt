package app.local1st.files.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.local1st.files.R
import app.local1st.files.core.cast.CastPlaybackBridge
import app.local1st.files.ui.viewer.CastPlaybackSessionManager
import app.local1st.files.ui.viewer.MediaViewer

/** Browser mini-player plus an in-app route back to the still-running remote playback controls. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun EditionCastPlaybackOverlay(browserVisible: Boolean) {
    val active by CastPlaybackSessionManager.activePlayback.collectAsStateWithLifecycle()
    var showControls by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        CastPlaybackSessionManager.openControlRequests.collect {
            if (CastPlaybackSessionManager.activePlayback.value != null) showControls = true
        }
    }
    LaunchedEffect(active) {
        if (active == null) showControls = false
    }

    val playback = active ?: return
    BackHandler(enabled = showControls) { showControls = false }

    if (showControls) {
        Box(Modifier.fillMaxSize()) {
            MediaViewer(
                entry = playback.entry,
                playlist = playback.playlist,
                onClose = { showControls = false },
            )
        }
        return
    }

    if (!browserVisible) return

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        ElevatedCard(
            onClick = { showControls = true },
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(horizontal = 16.dp, vertical = 100.dp)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CastConnected,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.cast_now_playing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        playback.entry.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = {
                        CastPlaybackBridge.togglePlayPause()
                    },
                ) {
                    Icon(
                        if (playback.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = stringResource(
                            if (playback.playing) R.string.pause else R.string.play,
                        ),
                    )
                }
            }
        }
    }
}

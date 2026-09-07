package app.local1st.files.ui.viewer

import androidx.compose.runtime.Composable
import androidx.media3.common.util.UnstableApi
import app.local1st.files.core.fs.XEntry

/**
 * Marks which browser pane opened a normal media viewer before the mobile Cast manager is
 * acquired. The three-argument MediaViewer remains reserved for the Cast overlay, which has no
 * browser-pane hint and therefore reattaches to the already-running remote session.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaViewer(
    entry: XEntry,
    playlist: List<XEntry>,
    sourcePaneId: Int,
    onClose: () -> Unit,
) {
    CastPlaybackSessionManager.setPendingViewerPane(sourcePaneId)
    MediaViewer(entry = entry, playlist = playlist, onClose = onClose)
    CastPlaybackSessionManager.clearPendingViewerPane()
}

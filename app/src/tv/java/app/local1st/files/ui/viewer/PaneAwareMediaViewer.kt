package app.local1st.files.ui.viewer

import androidx.compose.runtime.Composable
import app.local1st.files.core.fs.XEntry

/** TV is single-pane and has no Cast route ownership to isolate. */
@Composable
fun MediaViewer(
    entry: XEntry,
    playlist: List<XEntry>,
    sourcePaneId: Int,
    onClose: () -> Unit,
) {
    MediaViewer(entry = entry, playlist = playlist, onClose = onClose)
}

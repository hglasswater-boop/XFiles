package app.local1st.files.ui.dialogs

import app.local1st.files.core.fs.XEntry
import app.local1st.files.ui.main.MainViewModel

/** Opens the selected folder at the same location in the pane opposite the invoking pane. */
internal fun MainViewModel.openFolderInOtherPane(entry: XEntry) {
    if (!entry.isDir) return
    panes[1 - activePane.value].revealPath(entry.id)
}

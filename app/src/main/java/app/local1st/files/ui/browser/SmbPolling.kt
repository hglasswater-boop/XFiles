package app.local1st.files.ui.browser

import app.local1st.files.core.fs.XId

internal const val SMB_POLL_INTERVAL_MS = 5_000L

/**
 * Returns only the expanded SMB directories that currently represent real remote listings.
 * The synthetic smb:// root is backed by saved connection settings, not by a network directory,
 * so polling it would only add needless work.
 *
 * Selection is intentionally not part of this policy. [PaneController.refresh] replaces the
 * cached child listing without clearing the controller's id-based selection state, so surviving
 * checked rows stay checked across a poll.
 */
internal fun expandedSmbDirectoryIds(state: PaneUiState): List<String> {
    if (!state.startupSettled || state.snapshotOnly) return emptyList()
    val syntheticRoot = "${XId.SCHEME_SMB}://"
    return state.nodes.asSequence()
        .filter { node ->
            node.expanded &&
                node.entry.isContainer &&
                node.entry.scheme == XId.SCHEME_SMB &&
                node.entry.id != syntheticRoot
        }
        .map { it.entry.id }
        .distinct()
        .toList()
}

package app.local1st.files.ui.browser

import android.os.FileObserver
import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileWatchingTest {

    @Test
    fun `watches only expanded local directories`() {
        val state = PaneUiState(
            nodes = listOf(
                node("file:///storage/emulated/0", isDir = true, expanded = true),
                node("file:///storage/emulated/0/Download", isDir = true, expanded = true),
                node("file:///storage/emulated/0/DCIM", isDir = true, expanded = false),
                node("file:///storage/emulated/0/a.txt", isDir = false, expanded = true),
                node("smb://server/share", isDir = true, expanded = true),
                node(
                    "file:///storage/emulated/0/Download",
                    isDir = true,
                    expanded = true,
                    key = "duplicate",
                ),
            ),
            startupSettled = true,
        )

        assertEquals(
            listOf(
                LocalDirectoryWatchTarget(
                    "file:///storage/emulated/0",
                    "/storage/emulated/0",
                ),
                LocalDirectoryWatchTarget(
                    "file:///storage/emulated/0/Download",
                    "/storage/emulated/0/Download",
                ),
            ),
            expandedLocalDirectoryTargets(state),
        )
    }

    @Test
    fun `does not watch snapshot or unsettled startup tree`() {
        val nodes = listOf(node("file:///storage/emulated/0", isDir = true, expanded = true))

        assertTrue(
            expandedLocalDirectoryTargets(
                PaneUiState(nodes = nodes, startupSettled = false),
            ).isEmpty(),
        )
        assertTrue(
            expandedLocalDirectoryTargets(
                PaneUiState(nodes = nodes, startupSettled = true, snapshotOnly = true),
            ).isEmpty(),
        )
    }

    @Test
    fun `delete and move-out resolve the removed local entry id`() {
        val deleted = LocalDirectoryChange(
            directoryId = "file:///storage/emulated/0/Download",
            directoryPath = "/storage/emulated/0/Download",
            event = FileObserver.DELETE,
            childPath = "gone.mp4",
        )
        val moved = deleted.copy(event = FileObserver.MOVED_FROM, childPath = "folder")
        val created = deleted.copy(event = FileObserver.CREATE)

        assertEquals(
            "file:///storage/emulated/0/Download/gone.mp4",
            deleted.removedEntryId(),
        )
        assertEquals(
            "file:///storage/emulated/0/Download/folder",
            moved.removedEntryId(),
        )
        assertNull(created.removedEntryId())
    }

    private fun node(
        id: String,
        isDir: Boolean,
        expanded: Boolean,
        key: String = id,
    ) = TreeNode(
        entry = XEntry(id = id, name = id.substringAfterLast('/'), isDir = isDir),
        key = key,
        depth = 0,
        expanded = expanded,
        loading = false,
        guides = emptyList(),
        isLastChild = true,
    )
}

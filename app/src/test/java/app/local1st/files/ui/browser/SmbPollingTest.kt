package app.local1st.files.ui.browser

import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPollingTest {

    @Test
    fun `polls only expanded real SMB directories`() {
        val state = PaneUiState(
            nodes = listOf(
                node("smb://", isDir = true, expanded = true),
                node("smb://server/a", isDir = true, expanded = true),
                node("smb://server/b", isDir = true, expanded = false),
                node("smb://server/file.txt", isDir = false, expanded = true),
                node("file:///storage/emulated/0", isDir = true, expanded = true),
                node("smb://server/a", isDir = true, expanded = true, key = "duplicate"),
            ),
            startupSettled = true,
        )

        assertEquals(listOf("smb://server/a"), expandedSmbDirectoryIds(state))
    }

    @Test
    fun `does not poll while startup tree is still reconciling`() {
        val state = PaneUiState(
            nodes = listOf(node("smb://server/a", isDir = true, expanded = true)),
            startupSettled = false,
        )

        assertTrue(expandedSmbDirectoryIds(state).isEmpty())
    }

    @Test
    fun `target discovery leaves selection untouched`() {
        val selection = setOf(
            "smb://server/a/checked-one.mp4",
            "smb://server/a/checked-two.mp4",
        )
        val state = PaneUiState(
            nodes = listOf(node("smb://server/a", isDir = true, expanded = true)),
            selection = selection,
            startupSettled = true,
        )

        expandedSmbDirectoryIds(state)

        assertEquals(selection, state.selection)
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

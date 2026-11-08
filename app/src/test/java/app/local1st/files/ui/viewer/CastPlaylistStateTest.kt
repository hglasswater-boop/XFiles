package app.local1st.files.ui.viewer

import androidx.media3.common.MediaItem
import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPlaylistStateTest {
    private val old = listOf(
        XEntry(id = "smb://nas/old/a.mp4", name = "a.mp4", isDir = false),
        XEntry(id = "smb://nas/old/b.mp4", name = "b.mp4", isDir = false),
    )
    private val newer = listOf(
        XEntry(id = "smb://nas/new/c.mp4", name = "c.mp4", isDir = false),
        XEntry(id = "smb://nas/new/d.mp4", name = "d.mp4", isDir = false),
        XEntry(id = "smb://nas/new/e.mp4", name = "e.mp4", isDir = false),
    )

    private fun items(entries: List<XEntry>) =
        entries.map { MediaItem.Builder().setMediaId(it.id).build() }

    @Test
    fun switchingFolderUpdatesPresentedPlaylistOnSameStateObject() {
        val state = CastPlaylistState(old, items(old))

        state.replace(newer, items(newer))

        assertEquals(newer.map(XEntry::id), state.entryIds)
        assertEquals(newer, state.entries)
        assertEquals(newer.map(XEntry::id), state.mediaItems.map(MediaItem::mediaId))
        assertEquals(2, state.entryIds.indexOf(newer[2].id))
        assertEquals(-1, state.entryIds.indexOf(old[0].id))
    }

    @Test
    fun staleViewerDisposalDoesNotReleaseNewViewerOwner() {
        val oldOwner = Any()
        val newOwner = Any()

        assertFalse(isCurrentCastViewerOwner(newOwner, oldOwner))
        assertTrue(isCurrentCastViewerOwner(newOwner, newOwner))
    }

    @Test
    fun oldFolderResumeMustNotBeSavedAfterRemoteSelectionChangesFolder() {
        assertFalse(isMatchingPlaybackMediaId(old[0].id, newer[0].id))
        assertTrue(isMatchingPlaybackMediaId(newer[0].id, newer[0].id))
        assertFalse(isMatchingPlaybackMediaId(newer[0].id, null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun playlistRejectsMismatchedMediaItems() {
        CastPlaylistState(old, items(newer))
    }
}

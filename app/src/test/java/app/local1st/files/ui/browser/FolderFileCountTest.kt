package app.local1st.files.ui.browser

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderFileCountStateTest {
    @Test
    fun `publishing refreshed listing count replaces cache and notifies visible row`() = runBlocking {
        val state = FolderFileCountState(maxEntries = 4)
        val update = async(start = CoroutineStart.UNDISPATCHED) { state.updates.first() }
        val counts = FolderDirectCounts(folders = 2, files = 5)

        state.publish(
            key = "file:///parent|10",
            directoryId = "file:///parent",
            counts = counts,
        )

        assertEquals(counts, state.peek("file:///parent|10"))
        assertEquals(
            FolderFileCountUpdate(directoryId = "file:///parent", counts = counts),
            update.await(),
        )
    }

    @Test
    fun `invalidating dirty collapsed directory removes only its cache and requests reload`() = runBlocking {
        val state = FolderFileCountState(maxEntries = 4)
        state.publish(
            key = "file:///changed|1",
            directoryId = "file:///changed",
            counts = FolderDirectCounts(folders = 1, files = 1),
        )
        state.publish(
            key = "file:///other|1",
            directoryId = "file:///other",
            counts = FolderDirectCounts(folders = 3, files = 4),
        )
        val update = async(start = CoroutineStart.UNDISPATCHED) { state.updates.first() }

        state.invalidate("file:///changed")

        assertNull(state.peek("file:///changed|1"))
        assertEquals(FolderDirectCounts(3, 4), state.peek("file:///other|1"))
        assertEquals(
            FolderFileCountUpdate(directoryId = "file:///changed", counts = null),
            update.await(),
        )
    }
}

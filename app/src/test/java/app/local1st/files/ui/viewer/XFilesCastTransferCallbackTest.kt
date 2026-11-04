package app.local1st.files.ui.viewer

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XFilesCastTransferCallbackTest {
    private val first = MediaItem.Builder().setMediaId("folder-a/video-1").build()
    private val second = MediaItem.Builder().setMediaId("folder-a/video-2").build()
    private val third = MediaItem.Builder().setMediaId("folder-a/video-3").build()

    @Test
    fun castReceiverGetsOnlyCurrentFolderItem() {
        val receiverItems = castReceiverItems(
            mediaItems = listOf(first, second, third),
            currentMediaItemIndex = 1,
        )

        assertEquals(listOf(second), receiverItems)
    }

    @Test
    fun castReceiverGetsNoItemsWhenCurrentIndexIsUnset() {
        assertEquals(
            emptyList<MediaItem>(),
            castReceiverItems(listOf(first, second, third), C.INDEX_UNSET),
        )
    }

    @Test
    fun receiverItemMapsBackToLocalFolderIndex() {
        assertEquals(
            2,
            matchingLocalMediaIndex(listOf(first, second, third), third.mediaId),
        )
    }

    @Test
    fun unknownReceiverItemDoesNotMapToWrongFolderEntry() {
        assertNull(
            matchingLocalMediaIndex(
                listOf(first, second, third),
                "folder-b/video-1",
            ),
        )
    }
}

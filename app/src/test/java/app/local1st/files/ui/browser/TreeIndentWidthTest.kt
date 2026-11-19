package app.local1st.files.ui.browser

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeIndentWidthTest {
    @Test
    fun visibleGuideDepthUsesEightDpPerLevel() {
        assertEquals(0.dp, treeIndentWidth(0))
        assertEquals(8.dp, treeIndentWidth(1))
        assertEquals(16.dp, treeIndentWidth(2))
        assertEquals(32.dp, treeIndentWidth(4))
    }

    @Test
    fun negativeDepthHasNoIndent() {
        assertEquals(0.dp, treeIndentWidth(-1))
    }

    @Test
    fun thumbnailLeafReclaimsUnusedChevronSpace() {
        // The regular slot includes the 12dp chevron and its density-dependent lead pixel.
        val regularSlot = 13.dp
        assertEquals(4.dp, leafLeadingSlotWidth(wantsThumbnail = true, regularSlotWidth = regularSlot))
        assertEquals(regularSlot, leafLeadingSlotWidth(wantsThumbnail = false, regularSlotWidth = regularSlot))
    }

    @Test
    fun selectionMovesOnlyForSelectableThumbnailLeaves() {
        assertTrue(selectionFollowsThumbnail(wantsThumbnail = true, selectable = true))
        assertFalse(selectionFollowsThumbnail(wantsThumbnail = false, selectable = true))
        assertFalse(selectionFollowsThumbnail(wantsThumbnail = true, selectable = false))
        assertFalse(selectionFollowsThumbnail(wantsThumbnail = false, selectable = false))
    }

    @Test
    fun chevronKeepsOriginalTwelveDpCanvas() {
        assertEquals(12.dp, TreeChevronWidth)
    }
}

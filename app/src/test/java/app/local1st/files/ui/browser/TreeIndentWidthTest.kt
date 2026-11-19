package app.local1st.files.ui.browser

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
    fun chevronKeepsOriginalTwelveDpCanvas() {
        assertEquals(12.dp, TreeChevronWidth)
    }
}

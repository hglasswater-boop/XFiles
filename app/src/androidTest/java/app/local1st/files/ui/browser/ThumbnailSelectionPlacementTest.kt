package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ThumbnailSelectionPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactCircleRemainsAtRightEdgeAfterFilenameAndOnlySelects() {
        var selected = 0
        var rowOpened = 0
        compose.setContent {
            Row(Modifier.requiredWidth(320.dp).clickable { rowOpened++ }.testTag("file-row")) {
                Box(Modifier.requiredSize(104.dp, 72.dp).testTag("thumbnail"))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).height(72.dp).testTag("filename"))
                BrowserSelectionButton(
                    selected = false,
                    enabled = true,
                    compact = true,
                    rowMinHeight = 72.dp,
                    onToggleSelect = { selected++ },
                )
            }
        }

        val row = compose.onNodeWithTag("file-row", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val thumbnail = compose.onNodeWithTag("thumbnail", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val filename = compose.onNodeWithTag("filename", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val target = compose.onNodeWithTag("browser-selection-target", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val circle = compose.onNodeWithTag("browser-selection-glyph", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertNearDp(row.right.value, target.right.value)
        assertNearDp(filename.right.value, target.left.value)
        assertTrue("The circle must follow the filename, not the thumbnail",
            circle.left > filename.left && circle.left > thumbnail.right)
        assertNearDp(24f, (target.right - target.left).value)
        assertNearDp(20f, (circle.right - circle.left).value)
        assertNearDp(2f, (circle.left - target.left).value)
        assertNearDp(2f, (target.right - circle.right).value)

        compose.onNodeWithTag("browser-selection-target", useUnmergedTree = true).performClick()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(0, rowOpened)
        }
    }

    @Test
    fun nonThumbnailCircleRetainsCentered36DpTrailingSlot() {
        compose.setContent {
            Row(Modifier.requiredWidth(280.dp).testTag("file-row")) {
                Box(Modifier.weight(1f).height(48.dp).testTag("filename"))
                BrowserSelectionButton(
                    selected = false,
                    enabled = true,
                    compact = false,
                    rowMinHeight = 48.dp,
                    onToggleSelect = {},
                )
            }
        }

        val row = compose.onNodeWithTag("file-row", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val target = compose.onNodeWithTag("browser-selection-target", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val circle = compose.onNodeWithTag("browser-selection-glyph", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertNearDp(row.right.value, target.right.value)
        assertNearDp(36f, (target.right - target.left).value)
        assertNearDp(8f, (circle.left - target.left).value)
        assertNearDp(8f, (target.right - circle.right).value)
    }

    private fun assertNearDp(expected: Float, actual: Float) {
        assertEquals(expected, actual, 0.5f)
    }
}

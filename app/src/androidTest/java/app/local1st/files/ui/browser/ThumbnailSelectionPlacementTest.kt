package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThumbnailSelectionPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thumbnailSelectionCircleStartsTwoDpAfterThumbnailAndOnlySelects() {
        var selected = 0
        var rowOpened = 0
        compose.setContent {
            Row(Modifier.clickable { rowOpened++ }) {
                Box(Modifier.requiredSize(104.dp, 72.dp).testTag("thumbnail"))
                BrowserSelectionButton(
                    selected = false,
                    enabled = true,
                    adjacentToThumbnail = true,
                    rowMinHeight = 72.dp,
                    onToggleSelect = { selected++ },
                )
                Box(Modifier.requiredSize(60.dp, 72.dp))
            }
        }

        val thumbnail = compose.onNodeWithTag("thumbnail").getUnclippedBoundsInRoot()
        val target = compose.onNodeWithTag("browser-selection-target").getUnclippedBoundsInRoot()
        val circle = compose.onNodeWithTag(
            "browser-selection-glyph", useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()

        assertEquals(thumbnail.right, target.left)
        assertEquals(36.dp, target.right - target.left)
        assertEquals(20.dp, circle.right - circle.left)
        assertEquals(2.dp, circle.left - thumbnail.right)

        compose.onNodeWithTag("browser-selection-target").performClick()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(0, rowOpened)
        }
    }

    @Test
    fun nonThumbnailSelectionKeepsCenteredCircle() {
        compose.setContent {
            BrowserSelectionButton(
                selected = false,
                enabled = true,
                adjacentToThumbnail = false,
                rowMinHeight = 48.dp,
                onToggleSelect = {},
            )
        }

        val target = compose.onNodeWithTag("browser-selection-target").getUnclippedBoundsInRoot()
        val circle = compose.onNodeWithTag(
            "browser-selection-glyph", useUnmergedTree = true,
        ).getUnclippedBoundsInRoot()
        assertEquals(36.dp, target.right - target.left)
        assertEquals(8.dp, circle.left - target.left)
    }
}

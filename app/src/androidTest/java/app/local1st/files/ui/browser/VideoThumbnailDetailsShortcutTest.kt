package app.local1st.files.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VideoThumbnailDetailsShortcutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun infoButtonOpensDetailsWithoutTriggeringUnderlyingThumbnail() {
        var detailsOpened = 0
        var storyboardsOpened = 0
        compose.setContent {
            Box(
                Modifier
                    .requiredSize(104.dp, 72.dp)
                    .clickable { storyboardsOpened++ },
            ) {
                VideoThumbnailDetailsButton(
                    description = "動画の詳細情報",
                    onClick = { detailsOpened++ },
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }

        compose.onNodeWithContentDescription("動画の詳細情報").performClick()
        compose.runOnIdle {
            assertEquals(1, detailsOpened)
            assertEquals(0, storyboardsOpened)
        }
    }

    @Test
    fun whiteGlyphIsNearTopLeftAndTapSurfaceHasNoBlackBackground() {
        compose.setContent {
            Box(
                Modifier
                    .requiredSize(104.dp, 72.dp)
                    .background(Color.Magenta)
                    .testTag("video-thumbnail"),
            ) {
                VideoThumbnailDetailsButton(
                    description = "動画の詳細情報",
                    onClick = {},
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }

        val thumbnail = compose.onNodeWithTag("video-thumbnail")
        val thumbBounds = thumbnail.getUnclippedBoundsInRoot()
        val glyphBounds = compose.onNodeWithTag("video-info-glyph", useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("Info glyph should start within 4dp of the left edge",
            (glyphBounds.left - thumbBounds.left).value in 0f..4f)
        assertTrue("Info glyph should start within 4dp of the top edge",
            (glyphBounds.top - thumbBounds.top).value in 0f..4f)

        val buttonBounds = compose.onNodeWithContentDescription("動画の詳細情報")
            .getUnclippedBoundsInRoot()
        assertEquals(40.dp, buttonBounds.right - buttonBounds.left)
        assertEquals(40.dp, buttonBounds.bottom - buttonBounds.top)

        // A pixel inside the hit target, but away from the top-left glyph:
        // transparent touch surface must show the thumbnail rather than a dark disk.
        val pixels = thumbnail.captureToImage().toPixelMap()
        val pixel30 = (30 * compose.density.density).toInt()
        assertEquals(Color.Magenta, pixels[pixel30, pixel30])
    }
}

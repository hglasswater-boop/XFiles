package app.local1st.files.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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
}

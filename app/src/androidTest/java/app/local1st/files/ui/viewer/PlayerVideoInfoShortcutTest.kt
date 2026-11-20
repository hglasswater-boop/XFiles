package app.local1st.files.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.local1st.files.core.fs.XEntry
import app.local1st.files.ui.dialogs.FileDetailsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlayerVideoInfoShortcutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun playerInfoIconInvokesOnlyDetailsActionAndKeepsStandardTouchTarget() {
        var infoClicks = 0
        var videoTaps = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.Black).clickable { videoTaps++ }) {
                    PlayerVideoInfoButton(onClick = { infoClicks++ })
                }
            }
        }

        val button = compose.onNodeWithTag("player-video-info")
        val bounds = button.getUnclippedBoundsInRoot()
        assertEquals(48.dp, bounds.right - bounds.left)
        assertEquals(48.dp, bounds.bottom - bounds.top)
        button.performClick()
        compose.runOnIdle {
            assertEquals(1, infoClicks)
            assertEquals(0, videoTaps)
        }
    }

    @Test
    fun sharedDetailsDialogShowsVideoEntryWithoutClosingViewer() {
        val entry = XEntry(
            id = "file:///tmp/xfiles-video-details-test.mp4",
            name = "xfiles-video-details-test.mp4",
            isDir = false,
            size = 1024L,
            mime = "video/mp4",
        )
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                FileDetailsDialog(entry = entry, onDismiss = { dismissed++ })
            }
        }

        compose.onNodeWithText(entry.name).assertExists()
        compose.onNodeWithText("video/mp4", substring = true).assertExists()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }
}

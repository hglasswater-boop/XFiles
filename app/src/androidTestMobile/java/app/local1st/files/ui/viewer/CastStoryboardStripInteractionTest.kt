package app.local1st.files.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.media.formatVideoDuration
import app.local1st.files.ui.browser.StoryboardFrame
import app.local1st.files.ui.browser.StoryboardResult
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CastStoryboardStripInteractionTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var thumbnail: File
    private val entry = XEntry(id = "file:///test/video.mp4", name = "video.mp4", isDir = false)
    private val timestamp = 12_000L

    @Before
    fun prepareThumbnail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        thumbnail = File.createTempFile("storyboard-test-", ".png", context.cacheDir)
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLUE)
        thumbnail.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @After
    fun removeThumbnail() {
        if (::thumbnail.isInitialized) thumbnail.delete()
    }

    @Test
    fun verticalThumbnailSeeksBeforeRequestingDismissal() {
        val events = mutableListOf<String>()
        renderStoryboard(
            verticalColumns = 1,
            onSeek = { events += "seek:$it" },
            onThumbnailSelected = { events += "dismiss" },
        )

        compose.onNodeWithContentDescription(formatVideoDuration(timestamp)).performClick()

        compose.runOnIdle {
            assertEquals(listOf("seek:$timestamp", "dismiss"), events)
        }
    }

    @Test
    fun horizontalThumbnailSeeksWithoutRequestingDismissal() {
        var seekMs = -1L
        var dismissed = false
        renderStoryboard(
            verticalColumns = null,
            onSeek = { seekMs = it },
            onThumbnailSelected = { dismissed = true },
        )

        compose.onNodeWithContentDescription(formatVideoDuration(timestamp)).performClick()

        compose.runOnIdle {
            assertEquals(timestamp, seekMs)
            assertFalse(dismissed)
        }
    }

    @Test
    fun expandedVerticalDialogClosesAfterThumbnailSelection() {
        var seekMs = -1L
        renderStoryboard(verticalColumns = null, onSeek = { seekMs = it })

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.player_vertical_storyboard))
            .performClick()
        compose.onAllNodesWithContentDescription(formatVideoDuration(timestamp))[1].performClick()

        compose.runOnIdle { assertEquals(timestamp, seekMs) }
        compose.onNodeWithContentDescription(context.getString(R.string.close))
            .assertDoesNotExist()
    }

    private fun renderStoryboard(
        verticalColumns: Int?,
        onSeek: (Long) -> Unit,
        onThumbnailSelected: () -> Unit = {},
    ) {
        val result = StoryboardResult(
            durationMs = 60_000L,
            frames = listOf(StoryboardFrame(index = 0, timeMs = timestamp, file = thumbnail)),
        )
        compose.setContent {
            Box(Modifier.requiredSize(340.dp, 300.dp)) {
                CastStoryboardStrip(
                    entry = entry,
                    positionMs = 0L,
                    onSeek = onSeek,
                    verticalColumns = verticalColumns,
                    onThumbnailSelected = onThumbnailSelected,
                    sharedResult = result,
                    showJumpToCurrent = true,
                )
            }
        }
    }
}

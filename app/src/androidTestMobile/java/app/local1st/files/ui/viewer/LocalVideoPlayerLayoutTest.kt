package app.local1st.files.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalVideoPlayerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun landscapeVideoUsesFullHeightWhenStoryboardCollapsed() {
        val bounds = renderLayout(width = 600.dp, height = 300.dp, storyboardHeight = 126.dp)

        assertEquals(300f, bounds.video.height, 1f)
        assertEquals(1, bounds.verticalColumns)
        assertEquals(bounds.video.right, bounds.storyboard.left, 0.1f)
    }

    @Test
    fun landscapeVideoUsesFullHeightWhenFinePreviewExpanded() {
        val bounds = renderLayout(width = 600.dp, height = 300.dp, storyboardHeight = 252.dp, fine = true)

        assertEquals(300f, bounds.video.height, 1f)
        assertEquals(1, bounds.verticalColumns)
        assertTrue("storyboard should be to the right", bounds.storyboard.left >= bounds.video.right)
    }

    @Test
    fun landscapeSidebarUsesFixedSingleColumnWidth() {
        val bounds = renderLayout(width = 600.dp, height = 300.dp, storyboardHeight = 126.dp)

        assertEquals(472f, bounds.video.width, 1f)
        assertEquals(128f, bounds.storyboard.width, 1f)
        assertEquals(1, bounds.verticalColumns)
        assertEquals(bounds.video.right, bounds.storyboard.left, 0.1f)
    }

    @Test
    fun landscapeSidebarKeepsOnlyVerticalAndEndSafeInsets() {
        val insets = landscapeStoryboardSafeDrawingInsets(
            WindowInsets(left = 24, top = 3, right = 10, bottom = 7),
        )
        val density = Density(1f)

        assertEquals(0, insets.getLeft(density, LayoutDirection.Ltr))
        assertEquals(10, insets.getRight(density, LayoutDirection.Ltr))
        assertEquals(3, insets.getTop(density))
        assertEquals(7, insets.getBottom(density))
    }

    @Test
    fun landscapeSidebarDoesNotExposeLightParentAtCorners() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(
                    Modifier.requiredSize(600.dp, 300.dp)
                        .background(Color.White)
                        .testTag("container"),
                ) {
                    LocalVideoPlayerLayout(
                        inPictureInPicture = false,
                        storyboardHeight = 126.dp,
                        storyboardBottomClearance = 40.dp,
                        finePreviewVisible = false,
                        onDismissFinePreview = {},
                        videoContent = {
                            Box(Modifier.fillMaxSize().background(Color.Blue))
                        },
                        storyboardContent = {
                            Box(Modifier.fillMaxSize())
                        },
                    )
                }
            }
        }

        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("container").captureToImage().toPixelMap()
        val sidebarTopLeft = pixels[472, 0]
        assertTrue(sidebarTopLeft.red < 0.25f)
        assertTrue(sidebarTopLeft.green < 0.25f)
        assertTrue(sidebarTopLeft.blue < 0.25f)
    }

    @Test
    fun wideLandscapeKeepsSingleColumnSidebarAt128Dp() {
        val bounds = renderLayout(width = 840.dp, height = 300.dp, storyboardHeight = 126.dp)

        assertEquals(712f, bounds.video.width, 1f)
        assertEquals(128f, bounds.storyboard.width, 1f)
        assertEquals(1, bounds.verticalColumns)
        assertEquals(bounds.video.right, bounds.storyboard.left, 0.1f)
    }

    @Test
    fun portraitReservesClearanceGapAndStoryboardHeight() {
        val bounds = renderLayout(width = 300.dp, height = 600.dp, storyboardHeight = 126.dp, clearance = 40.dp)

        assertEquals(428f, bounds.video.bottom, 1f)
        assertEquals(428f, bounds.storyboard.top, 1f)
        assertEquals(554f, bounds.storyboard.bottom, 1f)
        assertEquals(126f, bounds.storyboard.height, 1f)
        assertNull(bounds.verticalColumns)
    }

    @Test
    fun squareContainerKeepsBottomStoryboard() {
        val bounds = renderLayout(width = 400.dp, height = 400.dp, storyboardHeight = 126.dp, clearance = 40.dp)

        assertEquals(228f, bounds.video.bottom, 1f)
        assertEquals(228f, bounds.storyboard.top, 1f)
        assertEquals(354f, bounds.storyboard.bottom, 1f)
        assertNull(bounds.verticalColumns)
    }

    @Test
    fun pictureInPictureUsesFullSizeAndHidesStoryboardForBothShapes() {
        var landscape by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val width = if (landscape) 600.dp else 300.dp
                val height = if (landscape) 300.dp else 600.dp
                Box(Modifier.requiredSize(width, height).testTag("container")) {
                    LocalVideoPlayerLayout(
                        inPictureInPicture = true,
                        storyboardHeight = 252.dp,
                        storyboardBottomClearance = 40.dp,
                        finePreviewVisible = true,
                        onDismissFinePreview = {},
                        videoContent = { inset ->
                            Box(Modifier.fillMaxSize().padding(bottom = inset).testTag("video"))
                        },
                        storyboardContent = { Box(Modifier.fillMaxSize().testTag("storyboard")) },
                    )
                }
            }
        }

        assertPictureInPictureSize(600f, 300f)
        compose.runOnIdle { landscape = false }
        compose.waitForIdle()
        assertPictureInPictureSize(300f, 600f)
    }

    @Test
    fun resizingCompositionRetainsVideoSlotRememberedState() {
        var landscape by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val width = if (landscape) 600.dp else 300.dp
                val height = if (landscape) 300.dp else 600.dp
                Box(Modifier.requiredSize(width, height).testTag("container")) {
                    LocalVideoPlayerLayout(
                        inPictureInPicture = false,
                        storyboardHeight = 126.dp,
                        storyboardBottomClearance = 40.dp,
                        finePreviewVisible = false,
                        onDismissFinePreview = {},
                        videoContent = { inset ->
                            var count by remember { mutableIntStateOf(0) }
                            Box(
                                Modifier.fillMaxSize().padding(bottom = inset)
                                    .clickable { count++ }.testTag("video"),
                            ) {
                                Text(count.toString())
                            }
                        },
                        storyboardContent = { Box(Modifier.fillMaxSize().testTag("storyboard")) },
                    )
                }
            }
        }

        compose.onNodeWithTag("video").performClick()
        compose.onNodeWithText("1").assertExists()
        compose.runOnIdle { landscape = true }
        compose.waitForIdle()
        compose.onNodeWithText("1").assertExists()
        compose.runOnIdle { landscape = false }
        compose.waitForIdle()
        compose.onNodeWithText("1").assertExists()
    }

    @Test
    fun tappingVideoOutsideFinePreviewDismissesIt() {
        var fine by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(600.dp, 300.dp).testTag("container")) {
                    LocalVideoPlayerLayout(
                        inPictureInPicture = false,
                        storyboardHeight = 126.dp,
                        storyboardBottomClearance = 40.dp,
                        finePreviewVisible = fine,
                        onDismissFinePreview = { fine = false },
                        videoContent = { inset ->
                            Box(Modifier.fillMaxSize().padding(bottom = inset).testTag("video"))
                        },
                        storyboardContent = { Box(Modifier.fillMaxSize().testTag("storyboard")) },
                    )
                }
            }
        }

        compose.onNodeWithTag("video").performTouchInput { click() }
        compose.runOnIdle { assertEquals(false, fine) }
    }

    private fun renderLayout(
        width: Dp,
        height: Dp,
        storyboardHeight: Dp,
        clearance: Dp = 40.dp,
        fine: Boolean = false,
    ): LayoutBounds {
        var storyboardVerticalColumns: Int? = null
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, height).background(Color.DarkGray).testTag("container")) {
                    LocalVideoPlayerLayout(
                        inPictureInPicture = false,
                        storyboardHeight = storyboardHeight,
                        storyboardBottomClearance = clearance,
                        finePreviewVisible = fine,
                        onDismissFinePreview = {},
                        videoContent = { inset ->
                            Box(
                                Modifier.fillMaxSize().padding(bottom = inset)
                                    .background(Color.Blue).testTag("video"),
                            )
                        },
                        storyboardContent = { verticalColumns ->
                            storyboardVerticalColumns = verticalColumns
                            Box(Modifier.fillMaxSize().testTag("storyboard"))
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val container = compose.onNodeWithTag("container").bounds()
        return LayoutBounds(
            video = compose.onNodeWithTag("video").bounds().relativeTo(container),
            storyboard = compose.onNodeWithTag("storyboard").bounds().relativeTo(container),
            verticalColumns = storyboardVerticalColumns,
        )
    }

    private fun assertPictureInPictureSize(width: Float, height: Float) {
        val container = compose.onNodeWithTag("container").bounds()
        val video = compose.onNodeWithTag("video").bounds().relativeTo(container)
        assertEquals(width, video.width, 1f)
        assertEquals(height, video.height, 1f)
        compose.onNodeWithTag("storyboard").assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun Rect.relativeTo(origin: Rect): Rect =
        Rect(left - origin.left, top - origin.top, right - origin.left, bottom - origin.top)

    private data class LayoutBounds(
        val video: Rect,
        val storyboard: Rect,
        val verticalColumns: Int?,
    )
}

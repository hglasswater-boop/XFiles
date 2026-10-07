package app.local1st.files.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val PLAYER_STORYBOARD_EDGE_GAP_DP = 6
private val PLAYER_STORYBOARD_SIDEBAR_WIDTH = 128.dp

/**
 * Owns the video/storyboard geometry while leaving playback and storyboard state with the caller.
 * Keep [videoContent] at one call site so a container resize does not replace the player subtree.
 */
@Composable
internal fun LocalVideoPlayerLayout(
    inPictureInPicture: Boolean,
    storyboardHeight: Dp,
    storyboardBottomClearance: Dp,
    finePreviewVisible: Boolean,
    onDismissFinePreview: () -> Unit,
    safeDrawingInsets: WindowInsets? = null,
    modifier: Modifier = Modifier,
    videoContent: @Composable (videoBottomInset: Dp) -> Unit,
    storyboardContent: @Composable (verticalColumns: Int?) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val landscape = !inPictureInPicture && maxWidth > maxHeight
        val resolvedSafeDrawingInsets = safeDrawingInsets ?: WindowInsets.safeDrawing
        val storyboardBottom = storyboardBottomClearance + PLAYER_STORYBOARD_EDGE_GAP_DP.dp
        val videoBottomInset = if (inPictureInPicture || landscape) {
            0.dp
        } else {
            storyboardBottom + storyboardHeight
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                videoContent(videoBottomInset)

                if (finePreviewVisible && !inPictureInPicture) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (landscape) Modifier else Modifier.padding(
                                    bottom = storyboardBottom + storyboardHeight,
                                ),
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismissFinePreview,
                            ),
                    )
                }
            }

            if (landscape) {
                Surface(
                    color = Color.Black.copy(alpha = 0.9f),
                    modifier = Modifier.width(PLAYER_STORYBOARD_SIDEBAR_WIDTH).fillMaxHeight(),
                ) {
                    Box(
                        Modifier.fillMaxSize().windowInsetsPadding(
                            resolvedSafeDrawingInsets.only(
                                WindowInsetsSides.End + WindowInsetsSides.Vertical,
                            ),
                        ),
                    ) {
                        storyboardContent(1)
                    }
                }
            }
        }

        if (!inPictureInPicture && !landscape) {
            Surface(
                color = Color.Black.copy(alpha = 0.9f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 4.dp, end = 4.dp, bottom = storyboardBottom)
                    .fillMaxWidth()
                    .height(storyboardHeight),
            ) {
                storyboardContent(null)
            }
        }
    }
}

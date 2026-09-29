package app.local1st.files.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryboardSeekSyncTest {
    @Test
    fun normalForwardPlaybackDoesNotAutoFollow() {
        assertFalse(
            shouldAutoFollowStoryboard(
                previousPositionMs = 10_000L,
                positionMs = 10_250L,
                elapsedRealtimeMs = 250L,
                previousNearestIndex = 2,
                nearestIndex = 3,
            ),
        )
    }

    @Test
    fun forwardSeekAcrossStoryboardFramesAutoFollows() {
        assertTrue(
            shouldAutoFollowStoryboard(
                previousPositionMs = 10_000L,
                positionMs = 20_000L,
                elapsedRealtimeMs = 200L,
                previousNearestIndex = 2,
                nearestIndex = 5,
            ),
        )
    }

    @Test
    fun backwardSeekAcrossStoryboardFramesAutoFollows() {
        assertTrue(
            shouldAutoFollowStoryboard(
                previousPositionMs = 20_000L,
                positionMs = 19_800L,
                elapsedRealtimeMs = 100L,
                previousNearestIndex = 5,
                nearestIndex = 4,
            ),
        )
    }

    @Test
    fun seekWithinSameStoryboardFrameDoesNotScroll() {
        assertFalse(
            shouldAutoFollowStoryboard(
                previousPositionMs = 10_000L,
                positionMs = 30_000L,
                elapsedRealtimeMs = 100L,
                previousNearestIndex = 2,
                nearestIndex = 2,
            ),
        )
    }

    @Test
    fun unknownStoryboardIndexDoesNotAutoFollow() {
        assertFalse(
            shouldAutoFollowStoryboard(
                previousPositionMs = 10_000L,
                positionMs = 30_000L,
                elapsedRealtimeMs = 100L,
                previousNearestIndex = -1,
                nearestIndex = 2,
            ),
        )
    }
}

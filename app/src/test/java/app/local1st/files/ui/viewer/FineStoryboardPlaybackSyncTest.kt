package app.local1st.files.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FineStoryboardPlaybackSyncTest {
    @Test
    fun staysOnCurrentSectionWhilePlaybackIsInsideFineWindow() {
        assertNull(
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 14_000L,
                positionMs = 14_999L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L),
            ),
        )
        assertNull(
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 14_999L,
                positionMs = 15_000L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L),
            ),
        )
    }

    @Test
    fun advancesAfterPlaybackPassesFineWindow() {
        assertEquals(
            2,
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 15_000L,
                positionMs = 15_001L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L, 35_000L),
            ),
        )
    }

    @Test
    fun skipsDirectlyToSectionContainingLargeForwardSeek() {
        assertEquals(
            4,
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 14_000L,
                positionMs = 42_000L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L, 35_000L, 45_000L),
            ),
        )
    }

    @Test
    fun doesNotMoveBackwardWhenPlaybackIsBehindDisplayedSection() {
        assertNull(
            fineStoryboardFollowTargetIndex(
                currentIndex = 3,
                previousPositionMs = 22_000L,
                positionMs = 12_000L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L, 35_000L, 45_000L),
            ),
        )
    }

    @Test
    fun finalSectionHasNoLaterTarget() {
        assertNull(
            fineStoryboardFollowTargetIndex(
                currentIndex = 2,
                previousPositionMs = 22_000L,
                positionMs = 99_000L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L),
            ),
        )
    }


    @Test
    fun manuallyOpenedPastSectionDoesNotJumpToPlaybackAlreadyBeyondItsWindow() {
        assertNull(
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 42_000L,
                positionMs = 43_000L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L, 35_000L, 45_000L),
            ),
        )
    }

    @Test
    fun forwardSeekFromAlreadyBeyondWindowKeepsManuallySelectedPastSection() {
        assertNull(
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 42_000L,
                positionMs = 60_000L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L, 35_000L, 45_000L),
            ),
        )
    }

    @Test
    fun playbackCanFollowAgainAfterReturningBehindManuallySelectedWindow() {
        assertEquals(
            2,
            fineStoryboardFollowTargetIndex(
                currentIndex = 1,
                previousPositionMs = 12_000L,
                positionMs = 15_001L,
                fineWindowEndTimesMs = listOf(5_000L, 15_000L, 25_000L, 35_000L),
            ),
        )
    }

    @Test
    fun windowEndUsesFiveFineStepsAndSaturatesOnOverflow() {
        assertEquals(15_000L, fineStoryboardWindowEndMs(centerTimeMs = 10_000L, stepMs = 1_000L))
        assertEquals(Long.MAX_VALUE, fineStoryboardWindowEndMs(Long.MAX_VALUE - 10L, 1_000L))
    }

    @Test
    fun windowEndMatchesFinePreviewClampingNearVideoEnd() {
        assertEquals(
            19_999L,
            fineStoryboardWindowEndMs(
                centerTimeMs = 18_000L,
                stepMs = 1_000L,
                durationMs = 20_000L,
            ),
        )
    }
}

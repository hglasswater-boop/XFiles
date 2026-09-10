package app.local1st.files.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHandoffTrackerTest {
    @Test
    fun newConnectionKeepsSelectedItemUntilRemoteAcknowledgesIt() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "video-b")

        tracker.beginRemoteHandoff()

        assertEquals("video-b", tracker.observe(isRemote = true, reportedMediaId = "video-a"))
        assertTrue(tracker.isPending)
        assertEquals("video-b", tracker.observe(isRemote = true, reportedMediaId = "video-b"))
        assertFalse(tracker.isPending)
    }

    @Test
    fun reusedSessionDifferentItemDoesNotAcceptPreviousRemoteItem() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "video-a")

        tracker.beginRemoteHandoff(explicitTargetMediaId = "video-c")

        assertEquals("video-c", tracker.observe(isRemote = true, reportedMediaId = "video-a"))
        assertTrue(tracker.isPending)
        assertEquals("video-c", tracker.observe(isRemote = true, reportedMediaId = "video-c"))
        assertFalse(tracker.isPending)
    }

    @Test
    fun sameRemoteItemCompletesImmediately() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "video-a")

        tracker.beginRemoteHandoff(explicitTargetMediaId = "video-a")

        assertEquals("video-a", tracker.observe(isRemote = true, reportedMediaId = "video-a"))
        assertFalse(tracker.isPending)
    }

    @Test
    fun differentPlaylistCanHoldTargetNotPresentInOldRemoteState() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "new-1")

        tracker.beginRemoteHandoff(explicitTargetMediaId = "new-1")

        assertEquals("new-1", tracker.observe(isRemote = true, reportedMediaId = "old-9"))
        assertTrue(tracker.isPending)
        assertEquals("new-1", tracker.observe(isRemote = true, reportedMediaId = "new-1"))
        assertFalse(tracker.isPending)
    }

    @Test
    fun newestSelectionWinsOverDelayedCallbackFromEarlierSelection() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "video-a")

        tracker.beginRemoteHandoff(explicitTargetMediaId = "video-b")
        tracker.beginRemoteHandoff(explicitTargetMediaId = "video-c")

        assertEquals("video-c", tracker.observe(isRemote = true, reportedMediaId = "video-b"))
        assertTrue(tracker.isPending)
        assertEquals("video-c", tracker.observe(isRemote = true, reportedMediaId = "video-c"))
        assertFalse(tracker.isPending)
    }

    @Test
    fun playbackFailureAbortsPendingHandoff() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "video-a")
        tracker.beginRemoteHandoff(explicitTargetMediaId = "video-b")

        assertEquals(
            "video-a",
            tracker.observe(
                isRemote = true,
                reportedMediaId = "video-a",
                playbackFailed = true,
            ),
        )
        assertFalse(tracker.isPending)
    }

    @Test
    fun disconnectAbortsPendingHandoffAndReturnsToLocalState() {
        val tracker = CastHandoffTracker(initialLocalMediaId = "video-a")
        tracker.beginRemoteHandoff(explicitTargetMediaId = "video-b")
        assertEquals("video-b", tracker.observe(isRemote = true, reportedMediaId = "video-a"))

        assertEquals("video-a", tracker.observe(isRemote = false, reportedMediaId = "video-a"))
        assertFalse(tracker.isPending)
    }

    @Test
    fun differentItemInReusedPlaylistAlwaysGetsExplicitDefaultSeekWithoutResume() {
        assertEquals(
            ReusedRemoteSelectionAction.SeekToDefault(mediaItemIndex = 2),
            reusedRemoteSelectionAction(
                currentMediaId = "video-a",
                targetMediaId = "video-c",
                targetIndex = 2,
                requestedStartMs = null,
                resolvedStartMs = null,
            ),
        )
    }

    @Test
    fun differentItemInReusedPlaylistUsesResolvedResumePosition() {
        assertEquals(
            ReusedRemoteSelectionAction.SeekToPosition(mediaItemIndex = 1, positionMs = 42_000L),
            reusedRemoteSelectionAction(
                currentMediaId = "video-a",
                targetMediaId = "video-b",
                targetIndex = 1,
                requestedStartMs = null,
                resolvedStartMs = 42_000L,
            ),
        )
    }

    @Test
    fun sameItemIsNotRestartedWithoutExplicitStartRequest() {
        assertEquals(
            ReusedRemoteSelectionAction.None,
            reusedRemoteSelectionAction(
                currentMediaId = "video-a",
                targetMediaId = "video-a",
                targetIndex = 0,
                requestedStartMs = null,
                resolvedStartMs = 42_000L,
            ),
        )
    }

    @Test
    fun sameItemHonorsExplicitStoryboardStartRequest() {
        assertEquals(
            ReusedRemoteSelectionAction.SeekToPosition(mediaItemIndex = 0, positionMs = 0L),
            reusedRemoteSelectionAction(
                currentMediaId = "video-a",
                targetMediaId = "video-a",
                targetIndex = 0,
                requestedStartMs = 0L,
                resolvedStartMs = 0L,
            ),
        )
    }
}

package app.local1st.files.ui.viewer

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastAutoAdvanceTest {
    private val items = listOf("a", "b", "c")

    private fun CastAutoAdvance.onState(
        mediaId: String? = "b",
        mode: CastAutoAdvanceMode = CastAutoAdvanceMode.NEXT,
        state: Int = Player.STATE_ENDED,
        pending: Boolean = false,
        error: Boolean = false,
        remote: Boolean = true,
    ): Int? = nextOnEnd(
        mode = mode,
        entryIds = items,
        selectedMediaId = mediaId,
        playbackState = state,
        handoffPending = pending,
        playbackFailed = error,
        isRemote = remote,
    )

    @Test
    fun defaultOffDoesNotAdvanceOrRetroactivelyAdvanceAfterChangingDirection() {
        val advance = CastAutoAdvance()
        assertNull(advance.onState(mode = CastAutoAdvanceMode.OFF))
        assertNull(advance.onState(mode = CastAutoAdvanceMode.NEXT))
    }

    @Test
    fun forwardAndBackwardFollowPlaylistOrder() {
        assertEquals(2, CastAutoAdvance().onState(mode = CastAutoAdvanceMode.NEXT))
        assertEquals(0, CastAutoAdvance().onState(mode = CastAutoAdvanceMode.PREVIOUS))
    }

    @Test
    fun boundariesDoNotWrapOrRestartTheVideo() {
        assertNull(CastAutoAdvance().onState(mediaId = "c", mode = CastAutoAdvanceMode.NEXT))
        assertNull(CastAutoAdvance().onState(mediaId = "a", mode = CastAutoAdvanceMode.PREVIOUS))
        assertNull(CastAutoAdvance().onState(mediaId = "missing"))
        assertNull(CastAutoAdvance().onState(mediaId = null))
    }

    @Test
    fun duplicateEndCallbacksNeverSkipTwoVideos() {
        val advance = CastAutoAdvance()
        assertEquals(2, advance.onState())
        assertNull(advance.onState())
        assertNull(advance.onState())
    }

    @Test
    fun successfulTransitionCanAdvanceAgainAfterNextVideoEnds() {
        val advance = CastAutoAdvance()
        assertEquals(2, advance.onState(mediaId = "b"))
        assertNull(advance.onState(mediaId = "b", pending = true))
        assertNull(advance.onState(mediaId = "c", state = Player.STATE_READY))
        assertNull(advance.onState(mediaId = "c")) // Last item in forward direction
    }

    @Test
    fun replayingSameVideoAfterReadyCanAdvanceAgain() {
        val advance = CastAutoAdvance()
        assertEquals(2, advance.onState())
        assertNull(advance.onState())
        assertNull(advance.onState(state = Player.STATE_READY))
        assertEquals(2, advance.onState())
    }

    @Test
    fun neverAdvanceDuringTransferOrNonTerminalPlayback() {
        assertNull(CastAutoAdvance().onState(pending = true))
        assertNull(CastAutoAdvance().onState(error = true))
        assertNull(CastAutoAdvance().onState(remote = false))
        assertNull(CastAutoAdvance().onState(state = Player.STATE_READY))
        assertNull(CastAutoAdvance().onState(state = Player.STATE_BUFFERING))
        assertNull(CastAutoAdvance().onState(state = Player.STATE_IDLE))
    }

    @Test
    fun modeChangedWhilePreviousVideoEndedDoesNotActUntilNewEnd() {
        val advance = CastAutoAdvance()
        assertNull(advance.onState(mode = CastAutoAdvanceMode.OFF))
        assertNull(advance.onState(mode = CastAutoAdvanceMode.PREVIOUS))
        assertNull(advance.onState(state = Player.STATE_READY))
        assertEquals(0, advance.onState(mode = CastAutoAdvanceMode.PREVIOUS))
    }
}

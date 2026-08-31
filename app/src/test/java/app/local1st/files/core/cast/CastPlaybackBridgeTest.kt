package app.local1st.files.core.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastPlaybackBridgeTest {
    private val controller = RecordingController()

    @After
    fun tearDown() {
        CastPlaybackBridge.detach(controller)
    }

    @Test
    fun forwardsTransportActionsToAttachedController() {
        CastPlaybackBridge.attach(controller, state(title = "movie.mp4", playing = true))

        CastPlaybackBridge.previous()
        CastPlaybackBridge.seekBy(-10_000L)
        CastPlaybackBridge.togglePlayPause()
        CastPlaybackBridge.seekBy(10_000L)
        CastPlaybackBridge.next()

        assertEquals(1, controller.previousCalls)
        assertEquals(listOf(-10_000L, 10_000L), controller.seekDeltas)
        assertEquals(1, controller.toggleCalls)
        assertEquals(1, controller.nextCalls)
    }

    @Test
    fun detachOnlyClearsTheMatchingController() {
        val newer = RecordingController()
        CastPlaybackBridge.attach(controller, state(title = "old.mp4", playing = true))
        CastPlaybackBridge.attach(newer, state(title = "new.mp4", playing = false))

        CastPlaybackBridge.detach(controller)

        assertEquals("new.mp4", CastPlaybackBridge.currentState()?.title)
        CastPlaybackBridge.togglePlayPause()
        assertEquals(1, newer.toggleCalls)

        CastPlaybackBridge.detach(newer)
        assertNull(CastPlaybackBridge.currentState())
    }

    private fun state(title: String, playing: Boolean) = CastPlaybackNotificationState(
        title = title,
        playing = playing,
        hasPrevious = true,
        hasNext = true,
    )

    private class RecordingController : CastPlaybackNotificationController {
        var toggleCalls = 0
        var previousCalls = 0
        var nextCalls = 0
        val seekDeltas = mutableListOf<Long>()

        override fun togglePlayPause() {
            toggleCalls++
        }

        override fun seekBy(deltaMs: Long) {
            seekDeltas += deltaMs
        }

        override fun previous() {
            previousCalls++
        }

        override fun next() {
            nextCalls++
        }
    }
}

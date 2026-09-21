package app.local1st.files.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioOffsetTest {
    @Test
    fun `600ms resolves exactly at 44100Hz`() {
        val frames = microsToFrames(600_000L, 44_100)
        assertEquals(26_460L, frames)
        assertEquals(600_000L, framesToMicros(frames, 44_100))
    }

    @Test
    fun `50ms resolves exactly at 48000Hz`() {
        val frames = microsToFrames(50_000L, 48_000)
        assertEquals(2_400L, frames)
        assertEquals(50_000L, framesToMicros(frames, 48_000))
    }

    @Test
    fun `controller clamps offsets and reports changes`() {
        val controller = PlaybackAudioOffsetController()
        assertEquals(0L, controller.offsetMs())
        assertTrue(controller.setOffsetMs(-600L))
        assertEquals(-600L, controller.offsetMs())
        assertFalse(controller.setOffsetMs(-600L))
        assertTrue(controller.setOffsetMs(9_999L))
        assertEquals(3_000L, controller.offsetMs())
    }
}

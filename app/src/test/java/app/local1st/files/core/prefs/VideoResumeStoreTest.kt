package app.local1st.files.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoResumeStoreTest {
    @Test
    fun `positions before five seconds are not resumed`() {
        assertNull(normalizeVideoResumePosition(4_999L, 60_000L))
    }

    @Test
    fun `middle position is retained`() {
        assertEquals(42_000L, normalizeVideoResumePosition(42_000L, 120_000L))
    }

    @Test
    fun `last five percent of a short video is treated as completed`() {
        assertNull(normalizeVideoResumePosition(57_500L, 60_000L))
    }

    @Test
    fun `end guard is capped at ten seconds for long videos`() {
        assertEquals(3_580_000L, normalizeVideoResumePosition(3_580_000L, 3_600_000L))
        assertNull(normalizeVideoResumePosition(3_591_000L, 3_600_000L))
    }

    @Test
    fun `position can be stored before duration is known`() {
        assertEquals(30_000L, normalizeVideoResumePosition(30_000L, 0L))
    }
}

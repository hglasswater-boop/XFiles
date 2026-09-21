package app.local1st.files.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LipSyncOffsetEstimatorTest {
    @Test
    fun `audio delayed by 600ms recommends advancing audio by 600ms`() {
        val mouth = DoubleArray(200)
        listOf(30, 55, 83, 116, 151).forEach { index ->
            mouth[index] = 1.0
            mouth[index + 1] = 0.45
        }
        val audio = DoubleArray(200)
        val delaySamples = 12 // 12 * 50ms = 600ms
        mouth.indices.forEach { index ->
            val target = index + delaySamples
            if (target in audio.indices) audio[target] = mouth[index]
        }

        val estimate = estimateLipSyncOffset(mouth, audio)
        assertNotNull(estimate)
        assertEquals(-600L, estimate!!.offsetMs)
        assertTrue(estimate.correlation > 0.9)
    }

    @Test
    fun `audio leading by 300ms recommends delaying audio by 300ms`() {
        val mouth = DoubleArray(180)
        listOf(35, 72, 108, 142).forEach { mouth[it] = 1.0 }
        val audio = DoubleArray(180)
        val leadSamples = 6 // 300ms
        mouth.indices.forEach { index ->
            val target = index - leadSamples
            if (target in audio.indices) audio[target] = mouth[index]
        }

        val estimate = estimateLipSyncOffset(mouth, audio)
        assertNotNull(estimate)
        assertEquals(300L, estimate!!.offsetMs)
    }

    @Test
    fun `flat signals are rejected`() {
        val estimate = estimateLipSyncOffset(DoubleArray(200), DoubleArray(200))
        assertEquals(null, estimate)
    }
}

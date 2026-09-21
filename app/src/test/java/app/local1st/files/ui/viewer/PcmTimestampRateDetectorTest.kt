package app.local1st.files.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmTimestampRateDetectorTest {
    @Test
    fun normal44100Timeline_keepsDeclaredRate() {
        val detector = PcmTimestampRateDetector(44_100)
        var decision: Int? = null
        var ptsUs = 0L

        repeat(12) {
            decision = detector.observe(ptsUs, 1024) ?: decision
            ptsUs += 1_024_000_000L / 44_100L
        }

        assertEquals(44_100, decision)
    }

    @Test
    fun issue152ProvidedCadence_keepsDeclared44100Rate() {
        val detector = PcmTimestampRateDetector(44_100)
        val ptsUs = longArrayOf(
            32_993,
            56_213,
            79_433,
            102_653,
            125_873,
            149_093,
            172_313,
            195_533,
            218_753,
            241_973,
            265_193,
            288_413,
        )

        var decision: Int? = null
        for (pts in ptsUs) {
            decision = detector.observe(pts, 1024) ?: decision
        }

        assertEquals(44_100, decision)
    }

    @Test
    fun providedSampleCadence_detects48000Timeline() {
        val detector = PcmTimestampRateDetector(44_100)
        val ptsUs = longArrayOf(
            104_082,
            127_302,
            150_522,
            168_073,
            191_293,
            214_512,
            237_732,
            253_401,
            276_621,
            299_841,
            323_061,
            338_730,
            361_950,
            385_170,
            408_390,
            424_082,
            447_302,
            470_522,
            493_719,
            509_410,
        )

        var decision: Int? = null
        for (pts in ptsUs) {
            decision = detector.observe(pts, 1024) ?: decision
        }

        assertEquals(48_000, decision)
    }

    @Test
    fun severeTimestampBreak_resetsProbeInsteadOfFalseCorrection() {
        val detector = PcmTimestampRateDetector(44_100)
        val normalStepUs = 1_024_000_000L / 44_100L
        var ptsUs = 0L

        repeat(5) {
            assertNull(detector.observe(ptsUs, 1024))
            ptsUs += normalStepUs
        }

        // A cut/edit style near-zero delta must restart the probe window.
        assertNull(detector.observe(ptsUs + 20, 1024))

        var decision: Int? = null
        ptsUs += 20
        repeat(12) {
            ptsUs += normalStepUs
            decision = detector.observe(ptsUs, 1024) ?: decision
        }

        assertEquals(44_100, decision)
    }
}

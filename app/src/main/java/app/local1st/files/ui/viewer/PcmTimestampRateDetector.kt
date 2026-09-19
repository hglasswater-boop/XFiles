package app.local1st.files.ui.viewer

import kotlin.math.abs

/**
 * Infers the timeline sample rate from decoded PCM frame counts and consecutive presentation times.
 *
 * This is deliberately independent from Android/Media3 APIs so both Mobile and TV unit-test
 * variants exercise the same decision logic.
 */
internal class PcmTimestampRateDetector(
    private val declaredSampleRate: Int,
) {
    private var windowStartPtsUs: Long? = null
    private var previousPtsUs: Long? = null
    private var previousFrameCount = 0
    private var framesBeforeCurrent = 0L
    private var validTransitions = 0
    private var allTransitionsNearDeclared = true

    fun observe(presentationTimeUs: Long, frameCount: Int): Int? {
        if (frameCount <= 0 || declaredSampleRate <= 0) return declaredSampleRate

        val previousPts = previousPtsUs
        if (previousPts == null) {
            resetWindow(presentationTimeUs, frameCount)
            return null
        }

        val deltaUs = presentationTimeUs - previousPts
        val expectedPreviousDurationUs =
            previousFrameCount.toDouble() * MICROS_PER_SECOND / declaredSampleRate
        if (
            deltaUs <= 0L ||
            deltaUs < expectedPreviousDurationUs * DISCONTINUITY_MIN_RATIO ||
            deltaUs > expectedPreviousDurationUs * DISCONTINUITY_MAX_RATIO
        ) {
            resetWindow(presentationTimeUs, frameCount)
            return null
        }

        val perBufferRatio = deltaUs / expectedPreviousDurationUs
        if (abs(perBufferRatio - 1.0) > NORMAL_TRANSITION_TOLERANCE) {
            allTransitionsNearDeclared = false
        }

        framesBeforeCurrent += previousFrameCount
        validTransitions += 1
        previousPtsUs = presentationTimeUs
        previousFrameCount = frameCount

        if (validTransitions >= NORMAL_FAST_PATH_TRANSITIONS && allTransitionsNearDeclared) {
            return declaredSampleRate
        }

        val windowStart = checkNotNull(windowStartPtsUs)
        val elapsedUs = presentationTimeUs - windowStart
        if (validTransitions >= RATE_PROBE_TRANSITIONS && elapsedUs > 0L) {
            val effectiveRate = framesBeforeCurrent.toDouble() * MICROS_PER_SECOND / elapsedUs
            val snapped = nearestStandardRate(effectiveRate)
            if (snapped != null) return snapped
        }

        if (validTransitions >= MAX_PROBE_TRANSITIONS) return declaredSampleRate
        return null
    }

    private fun resetWindow(presentationTimeUs: Long, frameCount: Int) {
        windowStartPtsUs = presentationTimeUs
        previousPtsUs = presentationTimeUs
        previousFrameCount = frameCount
        framesBeforeCurrent = 0L
        validTransitions = 0
        allTransitionsNearDeclared = true
    }

    private fun nearestStandardRate(effectiveRate: Double): Int? {
        val nearest = STANDARD_SAMPLE_RATES.minBy { abs(it - effectiveRate) }
        val relativeError = abs(nearest - effectiveRate) / nearest
        if (relativeError > STANDARD_RATE_SNAP_TOLERANCE) return null

        val declaredDifference = abs(nearest - declaredSampleRate).toDouble() / declaredSampleRate
        return if (declaredDifference < MATERIAL_RATE_DIFFERENCE) declaredSampleRate else nearest
    }

    private companion object {
        const val MICROS_PER_SECOND = 1_000_000.0
        const val DISCONTINUITY_MIN_RATIO = 0.4
        const val DISCONTINUITY_MAX_RATIO = 2.5
        const val NORMAL_TRANSITION_TOLERANCE = 0.03
        const val NORMAL_FAST_PATH_TRANSITIONS = 8
        const val RATE_PROBE_TRANSITIONS = 16
        const val MAX_PROBE_TRANSITIONS = 32
        const val STANDARD_RATE_SNAP_TOLERANCE = 0.015
        const val MATERIAL_RATE_DIFFERENCE = 0.02

        val STANDARD_SAMPLE_RATES = intArrayOf(
            8_000,
            11_025,
            12_000,
            16_000,
            22_050,
            24_000,
            32_000,
            44_100,
            48_000,
            88_200,
            96_000,
            176_400,
            192_000,
        )
    }
}

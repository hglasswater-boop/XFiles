package app.local1st.files.ui.viewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal data class LipSyncEstimate(
    /** Signed playback audio offset: negative advances audio, positive delays it. */
    val offsetMs: Long,
    val confidence: Float,
    val correlation: Double,
)

/**
 * Estimate a fixed playback audio offset from equally spaced mouth-motion and speech-onset signals.
 *
 * For a candidate [offsetMs], mouth sample i is compared with source-audio sample
 * `i - offsetMs / sampleStepMs`. Therefore an audio event that happens 600 ms after the matching
 * mouth motion resolves to -600 ms, exactly matching [PlaybackAudioOffsetController] semantics.
 */
internal fun estimateLipSyncOffset(
    mouthMotion: DoubleArray,
    speechOnset: DoubleArray,
    sampleStepMs: Int = 50,
    maxOffsetMs: Int = 1_500,
): LipSyncEstimate? {
    if (sampleStepMs <= 0 || maxOffsetMs < 0) return null
    val count = minOf(mouthMotion.size, speechOnset.size)
    if (count < MIN_SIGNAL_SAMPLES) return null

    val mouth = normalizeSignal(mouthMotion.copyOf(count)) ?: return null
    val audio = normalizeSignal(speechOnset.copyOf(count)) ?: return null
    val maxShift = maxOffsetMs / sampleStepMs

    var bestShift = 0
    var bestCorrelation = Double.NEGATIVE_INFINITY
    val scores = ArrayList<Pair<Int, Double>>(maxShift * 2 + 1)
    for (shift in -maxShift..maxShift) {
        val score = correlationAtShift(mouth, audio, shift) ?: continue
        scores += shift to score
        if (score > bestCorrelation) {
            bestCorrelation = score
            bestShift = shift
        }
    }
    if (!bestCorrelation.isFinite()) return null

    val exclusionRadius = max(1, SECOND_PEAK_EXCLUSION_MS / sampleStepMs)
    val secondBest = scores
        .asSequence()
        .filter { (shift, _) -> abs(shift - bestShift) > exclusionRadius }
        .maxOfOrNull { it.second }
        ?: -1.0
    val margin = (bestCorrelation - secondBest).coerceAtLeast(0.0)

    // Confidence is deliberately conservative. A broad/ambiguous peak should be shown as a weak
    // hint rather than silently modifying playback.
    val correlationScore = ((bestCorrelation - MIN_USEFUL_CORRELATION) /
        (1.0 - MIN_USEFUL_CORRELATION)).coerceIn(0.0, 1.0)
    val marginScore = (margin / STRONG_PEAK_MARGIN).coerceIn(0.0, 1.0)
    val confidence = (correlationScore * 0.7 + marginScore * 0.3).toFloat()

    return LipSyncEstimate(
        offsetMs = bestShift.toLong() * sampleStepMs,
        confidence = confidence,
        correlation = bestCorrelation,
    )
}

private fun correlationAtShift(
    mouth: DoubleArray,
    audio: DoubleArray,
    offsetShift: Int,
): Double? {
    var sum = 0.0
    var pairs = 0
    for (mouthIndex in mouth.indices) {
        val audioIndex = mouthIndex - offsetShift
        if (audioIndex !in audio.indices) continue
        sum += mouth[mouthIndex] * audio[audioIndex]
        pairs++
    }
    if (pairs < MIN_OVERLAP_SAMPLES) return null
    return sum / pairs
}

private fun normalizeSignal(input: DoubleArray): DoubleArray? {
    if (input.isEmpty()) return null
    val mean = input.average()
    var sumSquares = 0.0
    for (value in input) {
        val d = value - mean
        sumSquares += d * d
    }
    val stdDev = sqrt(sumSquares / input.size)
    if (!stdDev.isFinite() || stdDev < MIN_SIGNAL_STD_DEV) return null
    return DoubleArray(input.size) { index -> (input[index] - mean) / stdDev }
}

internal fun motionFromLevels(levels: DoubleArray): DoubleArray {
    if (levels.isEmpty()) return levels
    return DoubleArray(levels.size) { index ->
        if (index == 0) 0.0 else abs(levels[index] - levels[index - 1])
    }
}

private const val MIN_SIGNAL_SAMPLES = 60
private const val MIN_OVERLAP_SAMPLES = 40
private const val SECOND_PEAK_EXCLUSION_MS = 150
private const val MIN_USEFUL_CORRELATION = 0.20
private const val STRONG_PEAK_MARGIN = 0.15
private const val MIN_SIGNAL_STD_DEV = 1e-6

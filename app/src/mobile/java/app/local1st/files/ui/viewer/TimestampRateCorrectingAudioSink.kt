package app.local1st.files.ui.viewer

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Protects playback from malformed PCM timelines where decoded frame count and buffer PTS advance
 * at different sample rates.
 *
 * The concrete #124 sample declares/decodes AAC as 44.1 kHz but its packet PTS advances at almost
 * exactly the 48 kHz cadence. Media3's DefaultAudioSink therefore accumulates about 200 ms of
 * expected-vs-actual timestamp error and repeatedly performs a discontinuity correction. Because
 * the audio renderer owns the media clock, each correction stalls video frame release as well.
 *
 * We probe only the first few decoded PCM buffers, infer the effective sample rate from frame count
 * versus PTS, and snap only a clearly matching standard sample rate. Normal streams are forwarded
 * with their declared rate unchanged. The PCM bytes are not resampled: changing the sink input rate
 * intentionally reinterprets samples at the timeline rate, which is what the malformed stream's PTS
 * already describes.
 */
@UnstableApi
internal class TimestampRateCorrectingAudioSink(
    sink: AudioSink,
) : ForwardingAudioSink(sink) {
    private data class BufferedAudio(
        val buffer: ByteBuffer,
        val presentationTimeUs: Long,
        val encodedAccessUnitCount: Int,
    )

    private var pendingProbeConfig: AudioSink.AudioSinkConfig? = null
    private var detector: PcmTimestampRateDetector? = null
    private val bufferedAudio = ArrayDeque<BufferedAudio>()

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        if (!isProbeEligible(audioSinkConfig.format)) {
            clearProbe()
            super.configure(audioSinkConfig)
            return
        }

        pendingProbeConfig = audioSinkConfig
        detector = PcmTimestampRateDetector(audioSinkConfig.format.sampleRate)
        bufferedAudio.clear()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (pendingProbeConfig != null) {
            val config = checkNotNull(pendingProbeConfig)
            val frameSize = Util.getPcmFrameSize(config.format.pcmEncoding, config.format.channelCount)
            if (frameSize <= 0 || buffer.remaining() % frameSize != 0) {
                finishProbe(config.format.sampleRate)
            } else {
                val frames = buffer.remaining() / frameSize
                bufferedAudio.addLast(
                    BufferedAudio(
                        buffer = copyAndConsume(buffer),
                        presentationTimeUs = presentationTimeUs,
                        encodedAccessUnitCount = encodedAccessUnitCount,
                    ),
                )
                val decision = checkNotNull(detector).observe(presentationTimeUs, frames)
                if (decision != null) {
                    finishProbe(decision)
                }
                // We copied and consumed this renderer buffer. Buffered copies are drained below or
                // on the next callback if AudioTrack applies back pressure.
                drainBufferedAudio()
                return true
            }
        }

        if (!drainBufferedAudio()) return false
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun playToEndOfStream() {
        pendingProbeConfig?.let { finishProbe(it.format.sampleRate) }
        if (!drainBufferedAudio()) return
        super.playToEndOfStream()
    }

    override fun hasPendingData(): Boolean =
        bufferedAudio.isNotEmpty() || pendingProbeConfig != null || super.hasPendingData()

    override fun isEnded(): Boolean =
        bufferedAudio.isEmpty() && pendingProbeConfig == null && super.isEnded()

    override fun handleDiscontinuity() {
        pendingProbeConfig?.let { config ->
            bufferedAudio.clear()
            detector = PcmTimestampRateDetector(config.format.sampleRate)
        }
        super.handleDiscontinuity()
    }

    override fun flush() {
        bufferedAudio.clear()
        pendingProbeConfig?.let { config ->
            detector = PcmTimestampRateDetector(config.format.sampleRate)
        }
        super.flush()
    }

    override fun reset() {
        clearProbe()
        super.reset()
    }

    override fun release() {
        clearProbe()
        super.release()
    }

    private fun finishProbe(sampleRate: Int) {
        val original = pendingProbeConfig ?: return
        val corrected = if (sampleRate == original.format.sampleRate) {
            original
        } else {
            copyConfigWithSampleRate(original, sampleRate)
        }
        pendingProbeConfig = null
        detector = null
        super.configure(corrected)
    }

    private fun drainBufferedAudio(): Boolean {
        if (pendingProbeConfig != null) return false
        while (bufferedAudio.isNotEmpty()) {
            val head = bufferedAudio.first()
            if (!super.handleBuffer(
                    head.buffer,
                    head.presentationTimeUs,
                    head.encodedAccessUnitCount,
                )
            ) {
                return false
            }
            bufferedAudio.removeFirst()
        }
        return true
    }

    private fun clearProbe() {
        pendingProbeConfig = null
        detector = null
        bufferedAudio.clear()
    }

    private fun copyAndConsume(source: ByteBuffer): ByteBuffer {
        val duplicate = source.duplicate()
        val copy = ByteBuffer.allocateDirect(duplicate.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        copy.put(duplicate)
        copy.flip()
        source.position(source.limit())
        return copy
    }

    private fun copyConfigWithSampleRate(
        config: AudioSink.AudioSinkConfig,
        sampleRate: Int,
    ): AudioSink.AudioSinkConfig {
        val correctedFormat = config.format.buildUpon().setSampleRate(sampleRate).build()
        @Suppress("DEPRECATION")
        return AudioSink.AudioSinkConfig.Builder(correctedFormat)
            .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
            .setOutputChannelMapping(config.outputChannelMapping)
            .setTimeline(config.timeline)
            .setMediaPeriodId(config.mediaPeriodId)
            .build()
    }

    private fun isProbeEligible(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.sampleRate > 0 &&
            format.channelCount > 0 &&
            Util.isEncodingLinearPcm(format.pcmEncoding)
}

/** Pure timestamp/frame-count detector so the malformed cadence is covered by JVM tests. */
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

@UnstableApi
internal class TimestampRateCorrectingRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? =
        super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            ?.let(::TimestampRateCorrectingAudioSink)
}

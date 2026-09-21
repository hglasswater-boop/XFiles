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

/**
 * Protects playback from malformed PCM timelines where decoded frame count and buffer PTS advance
 * at different sample rates.
 *
 * The concrete #124 sample declares/decodes AAC as 44.1 kHz but its packet PTS advances at almost
 * exactly the 48 kHz cadence. Media3's DefaultAudioSink therefore accumulates about 200 ms of
 * expected-vs-actual timestamp error and repeatedly performs a discontinuity correction. Because
 * the audio renderer owns the media clock, each correction stalls video frame release as well.
 *
 * The probe is observational: normal PCM is forwarded to the real AudioSink immediately from the
 * first buffer. If a sustained timestamp/frame-count mismatch resolves to another standard sample
 * rate, the sink is reconfigured for subsequent audio. This keeps the #124 correction without
 * holding roughly 200 ms of otherwise healthy audio at startup, which can itself create visible
 * A/V offset on normal 44.1 kHz content.
 */
@UnstableApi
internal class TimestampRateCorrectingAudioSink(
    sink: AudioSink,
) : ForwardingAudioSink(sink) {
    private var probeConfig: AudioSink.AudioSinkConfig? = null
    private var detector: PcmTimestampRateDetector? = null
    private var lastObservedPresentationTimeUs: Long? = null

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        super.configure(audioSinkConfig)
        if (!isProbeEligible(audioSinkConfig.format)) {
            clearProbe()
            return
        }

        probeConfig = audioSinkConfig
        detector = PcmTimestampRateDetector(audioSinkConfig.format.sampleRate)
        lastObservedPresentationTimeUs = null
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        observeForRateCorrection(buffer, presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun playToEndOfStream() {
        clearProbe()
        super.playToEndOfStream()
    }

    override fun handleDiscontinuity() {
        resetProbeWindow()
        super.handleDiscontinuity()
    }

    override fun flush() {
        resetProbeWindow()
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

    private fun observeForRateCorrection(buffer: ByteBuffer, presentationTimeUs: Long) {
        val config = probeConfig ?: return
        val currentDetector = detector ?: return

        // MediaCodecAudioRenderer may retry the same ByteBuffer while AudioTrack is applying
        // back pressure. A retry must not look like a zero-duration timestamp discontinuity.
        if (lastObservedPresentationTimeUs == presentationTimeUs) return

        val frameSize = Util.getPcmFrameSize(config.format.pcmEncoding, config.format.channelCount)
        if (frameSize <= 0 || buffer.remaining() % frameSize != 0) {
            clearProbe()
            return
        }

        val frames = buffer.remaining() / frameSize
        lastObservedPresentationTimeUs = presentationTimeUs
        val decision = currentDetector.observe(presentationTimeUs, frames) ?: return
        finishProbe(decision)
    }

    private fun finishProbe(sampleRate: Int) {
        val original = probeConfig ?: return
        clearProbe()
        if (sampleRate != original.format.sampleRate) {
            // DefaultAudioSink supports configuration changes while data is queued. Let it drain the
            // already submitted short probe window, then apply the corrected rate without dropping
            // or replaying PCM. The malformed #124 stream is detected long before its old 200 ms
            // discontinuity threshold is reached.
            super.configure(copyConfigWithSampleRate(original, sampleRate))
        }
    }

    private fun resetProbeWindow() {
        val config = probeConfig ?: return
        detector = PcmTimestampRateDetector(config.format.sampleRate)
        lastObservedPresentationTimeUs = null
    }

    private fun clearProbe() {
        probeConfig = null
        detector = null
        lastObservedPresentationTimeUs = null
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
}

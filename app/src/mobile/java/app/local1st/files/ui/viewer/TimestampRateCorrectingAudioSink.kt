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

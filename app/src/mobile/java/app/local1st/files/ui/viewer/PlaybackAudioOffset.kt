package app.local1st.files.ui.viewer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import app.local1st.files.core.prefs.VIDEO_AV_SYNC_MAX_OFFSET_MS
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Process-scoped A/V offset state for the single local playback session. */
internal object PlaybackAudioOffset {
    val controller = PlaybackAudioOffsetController()
}

/**
 * Mutable playback offset shared by the player UI and the AudioSink.
 *
 * Negative values advance audio; positive values delay audio. A changed value takes effect at the
 * next AudioSink discontinuity/flush. The UI deliberately re-seeks the current player position
 * after changing the value so the new offset starts from a clean decoder boundary.
 */
internal class PlaybackAudioOffsetController(initialOffsetMs: Long = 0L) {
    private val offsetUs = AtomicLong(normalizeOffsetMs(initialOffsetMs) * 1_000L)

    fun offsetMs(): Long = offsetUs.get() / 1_000L

    fun setOffsetMs(offsetMs: Long): Boolean {
        val normalizedUs = normalizeOffsetMs(offsetMs) * 1_000L
        return offsetUs.getAndSet(normalizedUs) != normalizedUs
    }

    internal fun offsetUs(): Long = offsetUs.get()

    private fun normalizeOffsetMs(offsetMs: Long): Long =
        offsetMs.coerceIn(-VIDEO_AV_SYNC_MAX_OFFSET_MS, VIDEO_AV_SYNC_MAX_OFFSET_MS)
}

/**
 * Applies a user-selected fixed A/V correction to decoded linear PCM only.
 *
 * Audio advance is implemented by consuming the requested amount of PCM at every fresh playback
 * window (start/seek/discontinuity) and shifting the remaining PCM timestamps back by the same
 * duration. Audio delay is implemented by submitting zero PCM first, then shifting real PCM
 * timestamps forward. At 0 ms the wrapper is an exact pass-through.
 *
 * The wrapper intentionally sits outside [TimestampRateCorrectingAudioSink]. That keeps the #124
 * malformed-cadence correction intact while letting both the generated silence and trimmed PCM
 * retain internally consistent frame-count/timestamp cadence.
 */
@UnstableApi
internal class PlaybackOffsetAudioSink(
    sink: AudioSink,
    private val controller: PlaybackAudioOffsetController,
) : ForwardingAudioSink(sink) {
    private var pcmFormat: Format? = null
    private var frameSizeBytes = 0
    private var sampleRate = 0

    private var activeOffsetUs: Long? = null
    private var resolvedOffsetUs = 0L
    private var remainingAdvanceFrames = 0L
    private var delayBuffer: ByteBuffer? = null
    private var delayPresentationTimeUs = C.TIME_UNSET
    private var delaySubmitted = false

    private var trackedInputBuffer: ByteBuffer? = null
    private var trackedInputPresentationTimeUs = C.TIME_UNSET
    private var trackedOutputPresentationTimeUs = C.TIME_UNSET

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        super.configure(audioSinkConfig)
        val format = audioSinkConfig.format
        if (isLinearPcm(format)) {
            pcmFormat = format
            frameSizeBytes = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
            sampleRate = format.sampleRate
        } else {
            pcmFormat = null
            frameSizeBytes = 0
            sampleRate = 0
        }
        resetOffsetWindow()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (pcmFormat == null || frameSizeBytes <= 0 || sampleRate <= 0) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        ensureOffsetWindowInitialized()
        return when {
            resolvedOffsetUs == 0L ->
                super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            resolvedOffsetUs < 0L ->
                handleAdvancedAudio(buffer, presentationTimeUs, encodedAccessUnitCount)
            else ->
                handleDelayedAudio(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
    }

    override fun handleDiscontinuity() {
        resetOffsetWindow()
        super.handleDiscontinuity()
    }

    override fun flush() {
        resetOffsetWindow()
        super.flush()
    }

    override fun reset() {
        pcmFormat = null
        frameSizeBytes = 0
        sampleRate = 0
        resetOffsetWindow()
        super.reset()
    }

    override fun release() {
        pcmFormat = null
        resetOffsetWindow()
        super.release()
    }

    private fun ensureOffsetWindowInitialized() {
        if (activeOffsetUs != null) return
        val requestedUs = controller.offsetUs().coerceIn(
            -VIDEO_AV_SYNC_MAX_OFFSET_MS * 1_000L,
            VIDEO_AV_SYNC_MAX_OFFSET_MS * 1_000L,
        )
        activeOffsetUs = requestedUs
        if (requestedUs == 0L) {
            resolvedOffsetUs = 0L
            return
        }

        val frames = microsToFrames(abs(requestedUs), sampleRate)
        if (frames <= 0L) {
            resolvedOffsetUs = 0L
            return
        }
        val exactUs = framesToMicros(frames, sampleRate)
        resolvedOffsetUs = if (requestedUs < 0L) -exactUs else exactUs
        if (resolvedOffsetUs < 0L) {
            remainingAdvanceFrames = frames
        }
    }

    private fun handleAdvancedAudio(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        trackedOutputFor(buffer, presentationTimeUs)?.let { outputPts ->
            return forwardTracked(buffer, presentationTimeUs, outputPts, encodedAccessUnitCount)
        }

        var skippedWithinBufferFrames = 0L
        if (remainingAdvanceFrames > 0L) {
            val availableFrames = buffer.remaining() / frameSizeBytes
            if (availableFrames <= 0) return true

            if (availableFrames.toLong() <= remainingAdvanceFrames) {
                buffer.position(buffer.limit())
                remainingAdvanceFrames -= availableFrames.toLong()
                return true
            }

            skippedWithinBufferFrames = remainingAdvanceFrames
            val skipBytes = (skippedWithinBufferFrames * frameSizeBytes).toInt()
            buffer.position(buffer.position() + skipBytes)
            remainingAdvanceFrames = 0L
        }

        val skippedWithinBufferUs = framesToMicros(skippedWithinBufferFrames, sampleRate)
        val advanceUs = -resolvedOffsetUs
        val outputPts = shiftPts(
            presentationTimeUs,
            skippedWithinBufferUs - advanceUs,
        )
        return forwardTracked(buffer, presentationTimeUs, outputPts, encodedAccessUnitCount)
    }

    private fun handleDelayedAudio(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        trackedOutputFor(buffer, presentationTimeUs)?.let { outputPts ->
            return forwardTracked(buffer, presentationTimeUs, outputPts, encodedAccessUnitCount)
        }

        if (!delaySubmitted) {
            if (delayBuffer == null) {
                val frames = microsToFrames(resolvedOffsetUs, sampleRate)
                val byteCount = Math.multiplyExact(frames, frameSizeBytes.toLong()).toInt()
                delayBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
                delayPresentationTimeUs = presentationTimeUs
            }
            val pendingSilence = delayBuffer ?: return false
            val consumed = super.handleBuffer(
                pendingSilence,
                delayPresentationTimeUs,
                1,
            )
            if (!consumed) return false
            delayBuffer = null
            delaySubmitted = true
            // Keep the real renderer buffer untouched and let MediaCodecAudioRenderer retry it.
            return false
        }

        val outputPts = shiftPts(presentationTimeUs, resolvedOffsetUs)
        return forwardTracked(buffer, presentationTimeUs, outputPts, encodedAccessUnitCount)
    }

    private fun trackedOutputFor(buffer: ByteBuffer, presentationTimeUs: Long): Long? =
        trackedOutputPresentationTimeUs.takeIf {
            trackedInputBuffer === buffer && trackedInputPresentationTimeUs == presentationTimeUs
        }

    private fun forwardTracked(
        buffer: ByteBuffer,
        inputPresentationTimeUs: Long,
        outputPresentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        trackedInputBuffer = buffer
        trackedInputPresentationTimeUs = inputPresentationTimeUs
        trackedOutputPresentationTimeUs = outputPresentationTimeUs
        val consumed = super.handleBuffer(
            buffer,
            outputPresentationTimeUs,
            encodedAccessUnitCount,
        )
        if (consumed) clearTrackedInput()
        return consumed
    }

    private fun clearTrackedInput() {
        trackedInputBuffer = null
        trackedInputPresentationTimeUs = C.TIME_UNSET
        trackedOutputPresentationTimeUs = C.TIME_UNSET
    }

    private fun resetOffsetWindow() {
        activeOffsetUs = null
        resolvedOffsetUs = 0L
        remainingAdvanceFrames = 0L
        delayBuffer = null
        delayPresentationTimeUs = C.TIME_UNSET
        delaySubmitted = false
        clearTrackedInput()
    }

    private fun shiftPts(presentationTimeUs: Long, deltaUs: Long): Long =
        if (presentationTimeUs == C.TIME_UNSET) C.TIME_UNSET else presentationTimeUs + deltaUs

    private fun isLinearPcm(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.AUDIO_RAW &&
            format.sampleRate > 0 &&
            format.channelCount > 0 &&
            Util.isEncodingLinearPcm(format.pcmEncoding)
}

internal fun microsToFrames(durationUs: Long, sampleRate: Int): Long {
    if (durationUs <= 0L || sampleRate <= 0) return 0L
    return (durationUs * sampleRate + 500_000L) / 1_000_000L
}

internal fun framesToMicros(frames: Long, sampleRate: Int): Long {
    if (frames <= 0L || sampleRate <= 0) return 0L
    return frames * 1_000_000L / sampleRate
}

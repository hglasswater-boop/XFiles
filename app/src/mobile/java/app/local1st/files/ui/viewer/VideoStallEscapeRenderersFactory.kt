package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * #124 diagnostic/fix candidate.
 *
 * Keep Media3 audio rendering and its master MediaClock untouched. Only the position observed by
 * the video renderer is allowed to advance temporarily when that position falls substantially
 * behind elapsed realtime. This targets the ~200 ms frame-release stalls observed on-device while
 * avoiding a permanently independent video clock.
 */
@UnstableApi
internal class VideoStallEscapeRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> =
        super.createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput,
        ).map { renderer ->
            if (renderer.trackType == C.TRACK_TYPE_VIDEO) {
                StallEscapingVideoRenderer(renderer)
            } else {
                renderer
            }
        }.toTypedArray()
}

@UnstableApi
private class StallEscapingVideoRenderer(
    renderer: Renderer,
) : ForwardingRenderer(renderer) {
    private var playbackSpeed = 1f
    private var lastRawPositionUs = C.TIME_UNSET
    private var lastElapsedRealtimeUs = C.TIME_UNSET
    private var lastAdjustedPositionUs = C.TIME_UNSET
    private var accumulatedLagUs = 0L
    private var compensating = false

    private var cachedRawPositionUs = C.TIME_UNSET
    private var cachedElapsedRealtimeUs = C.TIME_UNSET
    private var cachedAdjustedPositionUs = C.TIME_UNSET

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(adjustPositionUs(positionUs, elapsedRealtimeUs), elapsedRealtimeUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        super.getDurationToProgressUs(
            adjustPositionUs(positionUs, elapsedRealtimeUs),
            elapsedRealtimeUs,
        )

    override fun setPlaybackSpeed(currentPlaybackSpeed: Float, targetPlaybackSpeed: Float) {
        playbackSpeed = currentPlaybackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)
        clearEstimator()
        super.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        clearEstimator()
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }

    override fun start() {
        clearEstimator()
        super.start()
    }

    override fun stop() {
        clearEstimator()
        super.stop()
    }

    override fun disable() {
        clearEstimator()
        super.disable()
    }

    override fun reset() {
        clearEstimator()
        super.reset()
    }

    private fun adjustPositionUs(rawPositionUs: Long, elapsedRealtimeUs: Long): Long {
        if (rawPositionUs == cachedRawPositionUs && elapsedRealtimeUs == cachedElapsedRealtimeUs) {
            return cachedAdjustedPositionUs
        }

        if (state != Renderer.STATE_STARTED) {
            return resetTo(rawPositionUs, elapsedRealtimeUs)
        }

        if (lastRawPositionUs == C.TIME_UNSET || lastElapsedRealtimeUs == C.TIME_UNSET) {
            return resetTo(rawPositionUs, elapsedRealtimeUs)
        }

        val elapsedDeltaUs = elapsedRealtimeUs - lastElapsedRealtimeUs
        val rawDeltaUs = rawPositionUs - lastRawPositionUs
        if (
            elapsedDeltaUs < 0L ||
            rawDeltaUs < -BACKWARD_DISCONTINUITY_US ||
            rawDeltaUs > FORWARD_DISCONTINUITY_US
        ) {
            return resetTo(rawPositionUs, elapsedRealtimeUs)
        }

        val expectedAdvanceUs = (elapsedDeltaUs * playbackSpeed).toLong().coerceAtLeast(0L)
        val lagDeltaUs = expectedAdvanceUs - rawDeltaUs
        accumulatedLagUs =
            (accumulatedLagUs + lagDeltaUs)
                .coerceIn(0L, MAX_VIDEO_LEAD_US)

        if (!compensating && accumulatedLagUs >= STALL_DETECTION_US) {
            compensating = true
            Log.i(TAG, "Video clock stall escape active: lag=${accumulatedLagUs / 1000}ms")
        }

        var adjustedPositionUs = rawPositionUs
        if (compensating) {
            val realtimeAdvancedUs =
                if (lastAdjustedPositionUs == C.TIME_UNSET) {
                    rawPositionUs
                } else {
                    lastAdjustedPositionUs + expectedAdvanceUs
                }
            adjustedPositionUs =
                maxOf(rawPositionUs, realtimeAdvancedUs)
                    .coerceAtMost(rawPositionUs + MAX_VIDEO_LEAD_US)

            val rawCaughtUp = rawPositionUs >= adjustedPositionUs - RECOVERY_TOLERANCE_US
            if (accumulatedLagUs <= RECOVERY_LAG_US && rawCaughtUp) {
                compensating = false
                accumulatedLagUs = 0L
                adjustedPositionUs = rawPositionUs
                Log.i(TAG, "Video clock stall escape recovered")
            }
        }

        lastRawPositionUs = rawPositionUs
        lastElapsedRealtimeUs = elapsedRealtimeUs
        lastAdjustedPositionUs = adjustedPositionUs
        cache(rawPositionUs, elapsedRealtimeUs, adjustedPositionUs)
        return adjustedPositionUs
    }

    private fun resetTo(rawPositionUs: Long, elapsedRealtimeUs: Long): Long {
        accumulatedLagUs = 0L
        compensating = false
        lastRawPositionUs = rawPositionUs
        lastElapsedRealtimeUs = elapsedRealtimeUs
        lastAdjustedPositionUs = rawPositionUs
        cache(rawPositionUs, elapsedRealtimeUs, rawPositionUs)
        return rawPositionUs
    }

    private fun cache(rawPositionUs: Long, elapsedRealtimeUs: Long, adjustedPositionUs: Long) {
        cachedRawPositionUs = rawPositionUs
        cachedElapsedRealtimeUs = elapsedRealtimeUs
        cachedAdjustedPositionUs = adjustedPositionUs
    }

    private fun clearEstimator() {
        lastRawPositionUs = C.TIME_UNSET
        lastElapsedRealtimeUs = C.TIME_UNSET
        lastAdjustedPositionUs = C.TIME_UNSET
        accumulatedLagUs = 0L
        compensating = false
        cachedRawPositionUs = C.TIME_UNSET
        cachedElapsedRealtimeUs = C.TIME_UNSET
        cachedAdjustedPositionUs = C.TIME_UNSET
    }

    private companion object {
        const val TAG = "XFilesVideoClock"
        const val MIN_PLAYBACK_SPEED = 0.01f
        const val STALL_DETECTION_US = 60_000L
        const val RECOVERY_LAG_US = 15_000L
        const val RECOVERY_TOLERANCE_US = 10_000L
        const val MAX_VIDEO_LEAD_US = 250_000L
        const val BACKWARD_DISCONTINUITY_US = 20_000L
        const val FORWARD_DISCONTINUITY_US = 500_000L
    }
}

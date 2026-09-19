package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import kotlin.math.abs

/**
 * #124 workaround under test.
 *
 * Keep the audio renderer as ExoPlayer's master clock, but bridge only short clock-reporting stalls
 * with elapsed realtime. Normal playback remains anchored to the AudioSink clock, avoiding the
 * long-term drift risk of permanently removing the audio MediaClock.
 */
@UnstableApi
internal class AudioClockIndependentRenderersFactory(
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
            if (renderer.trackType != C.TRACK_TYPE_AUDIO) {
                renderer
            } else {
                object : ForwardingRenderer(renderer) {
                    private var wrappedClock: MediaClock? = null

                    override fun getMediaClock(): MediaClock? {
                        wrappedClock?.let { return it }
                        val delegateClock = renderer.mediaClock ?: return null
                        return StallTolerantMediaClock(
                            delegate = delegateClock,
                            isRendererStarted = { renderer.state == Renderer.STATE_STARTED },
                        ).also { wrappedClock = it }
                    }
                }
            }
        }.toTypedArray()
}

@UnstableApi
private class StallTolerantMediaClock(
    private val delegate: MediaClock,
    private val isRendererStarted: () -> Boolean,
) : MediaClock {
    private var lastDelegatePositionUs = C.TIME_UNSET
    private var lastDelegateAdvanceRealtimeUs = C.TIME_UNSET
    private var lastReturnedPositionUs = C.TIME_UNSET
    private var lastCallRealtimeUs = C.TIME_UNSET

    override fun getPositionUs(): Long {
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        val delegatePositionUs = delegate.positionUs

        if (!isRendererStarted() || lastDelegatePositionUs == C.TIME_UNSET) {
            return reset(delegatePositionUs, nowUs)
        }

        val delegateDeltaUs = delegatePositionUs - lastDelegatePositionUs
        if (abs(delegateDeltaUs) >= CLOCK_DISCONTINUITY_RESET_US) {
            return reset(delegatePositionUs, nowUs)
        }

        val callDeltaUs = if (lastCallRealtimeUs == C.TIME_UNSET) 0L else nowUs - lastCallRealtimeUs
        lastCallRealtimeUs = nowUs

        if (delegateDeltaUs > 0L) {
            lastDelegatePositionUs = delegatePositionUs
            lastDelegateAdvanceRealtimeUs = nowUs

            val returned = if (
                lastReturnedPositionUs != C.TIME_UNSET &&
                delegatePositionUs < lastReturnedPositionUs
            ) {
                val maxForwardUs = (callDeltaUs * delegate.playbackParameters.speed).toLong()
                maxOf(delegatePositionUs, lastReturnedPositionUs + maxForwardUs)
                    .coerceAtMost(delegatePositionUs + MAX_STALL_COMPENSATION_US)
            } else {
                delegatePositionUs
            }
            lastReturnedPositionUs = returned
            return returned
        }

        val stalledForUs = nowUs - lastDelegateAdvanceRealtimeUs
        if (stalledForUs <= STALL_DETECTION_US) {
            val returned = maxOf(delegatePositionUs, lastReturnedPositionUs)
            lastReturnedPositionUs = returned
            return returned
        }

        val compensationUs = (
            (stalledForUs - STALL_DETECTION_US) * delegate.playbackParameters.speed
        ).toLong().coerceIn(0L, MAX_STALL_COMPENSATION_US)
        val returned = maxOf(
            lastReturnedPositionUs,
            lastDelegatePositionUs + compensationUs,
        )
        lastReturnedPositionUs = returned
        return returned
    }

    override fun hasSkippedSilenceSinceLastCall(): Boolean =
        delegate.hasSkippedSilenceSinceLastCall()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        delegate.playbackParameters = playbackParameters
        clearInterpolationState()
    }

    override fun getPlaybackParameters(): PlaybackParameters = delegate.playbackParameters

    private fun reset(delegatePositionUs: Long, nowUs: Long): Long {
        lastDelegatePositionUs = delegatePositionUs
        lastDelegateAdvanceRealtimeUs = nowUs
        lastReturnedPositionUs = delegatePositionUs
        lastCallRealtimeUs = nowUs
        return delegatePositionUs
    }

    private fun clearInterpolationState() {
        lastDelegatePositionUs = C.TIME_UNSET
        lastDelegateAdvanceRealtimeUs = C.TIME_UNSET
        lastReturnedPositionUs = C.TIME_UNSET
        lastCallRealtimeUs = C.TIME_UNSET
    }

    private companion object {
        const val STALL_DETECTION_US = 25_000L
        const val MAX_STALL_COMPENSATION_US = 250_000L
        const val CLOCK_DISCONTINUITY_RESET_US = 500_000L
    }
}

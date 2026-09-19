package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.widget.Toast
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
 * Issue #124 diagnostic inspired by VLC-style master-clock/follower synchronization.
 *
 * The audio renderer deliberately does not expose its MediaClock to ExoPlayer. This keeps video
 * scheduling on ExoPlayer's standalone clock, which is the only configuration that removed the
 * periodic ~200 ms freeze on the affected device. The real AudioRenderer clock is still observed
 * internally. If a drift persists long enough to be real rather than the known short clock-report
 * stall, only the audio sink is gently time-stretched until it converges on the standalone clock.
 *
 * This is intentionally not a libVLC dependency or a copy of VLC internals. It applies the same
 * broad synchronization idea: one stable master clock, a follower clock, a dead band, and gradual
 * correction instead of making the video wait for a temporarily stalled audio clock.
 */
@UnstableApi
internal class VlcLikeAvSyncRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    private val appContext = context.applicationContext

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
            if (renderer.trackType == C.TRACK_TYPE_AUDIO) {
                AdaptiveAudioFollowerRenderer(
                    delegate = renderer,
                    context = appContext,
                )
            } else {
                renderer
            }
        }.toTypedArray()
}

@UnstableApi
private class AdaptiveAudioFollowerRenderer(
    private val delegate: Renderer,
    context: Context,
) : ForwardingRenderer(delegate) {
    private val appContext = context.applicationContext
    private var filteredDriftUs = 0.0
    private var filterInitialized = false
    private var outsideDeadbandSinceMs = C.TIME_UNSET
    private var lastMasterPositionUs = C.TIME_UNSET
    private var lastRenderRealtimeUs = C.TIME_UNSET
    private var warmupUntilMs = SystemClock.elapsedRealtime() + WARMUP_MS
    private var appliedSpeed = 1f
    private var lastToastMs = 0L

    /**
     * Returning null is the key part of the experiment: DefaultMediaClock must use its standalone
     * clock instead of adopting AudioRenderer as the player clock.
     */
    override fun getMediaClock(): MediaClock? = null

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs, elapsedRealtimeUs)

        val nowMs = SystemClock.elapsedRealtime()
        val audioClock = delegate.mediaClock
        if (delegate.state != Renderer.STATE_STARTED || audioClock == null) {
            resetTracking(audioClock, nowMs)
            lastMasterPositionUs = positionUs
            lastRenderRealtimeUs = elapsedRealtimeUs
            return
        }

        if (isPositionDiscontinuity(positionUs, elapsedRealtimeUs)) {
            resetTracking(audioClock, nowMs)
            warmupUntilMs = nowMs + WARMUP_MS
            lastMasterPositionUs = positionUs
            lastRenderRealtimeUs = elapsedRealtimeUs
            return
        }
        lastMasterPositionUs = positionUs
        lastRenderRealtimeUs = elapsedRealtimeUs

        val driftUs = audioClock.positionUs - positionUs
        if (!filterInitialized) {
            filteredDriftUs = driftUs.toDouble()
            filterInitialized = true
        } else {
            filteredDriftUs += (driftUs - filteredDriftUs) * DRIFT_FILTER_ALPHA
        }

        if (nowMs < warmupUntilMs) {
            applyAudioSpeed(audioClock, 1f)
            return
        }

        val absDriftUs = abs(filteredDriftUs)
        if (absDriftUs <= DRIFT_DEADBAND_US) {
            outsideDeadbandSinceMs = C.TIME_UNSET
            applyAudioSpeed(audioClock, 1f)
            return
        }

        if (outsideDeadbandSinceMs == C.TIME_UNSET) {
            outsideDeadbandSinceMs = nowMs
            applyAudioSpeed(audioClock, 1f)
            return
        }
        if (nowMs - outsideDeadbandSinceMs < PERSISTENCE_MS) {
            applyAudioSpeed(audioClock, 1f)
            return
        }

        // Positive drift means audio is ahead of the standalone/video master, so slow audio down.
        // Negative drift means audio is behind, so speed it up. The clamp makes correction inaudible
        // in normal use while still recovering hundreds of milliseconds over tens of seconds.
        val correction = (-filteredDriftUs / CATCH_UP_WINDOW_US).toFloat()
            .coerceIn(-MAX_SPEED_DELTA, MAX_SPEED_DELTA)
        val targetSpeed = (1f + correction).coerceIn(
            1f - MAX_SPEED_DELTA,
            1f + MAX_SPEED_DELTA,
        )
        applyAudioSpeed(audioClock, targetSpeed)

        if (nowMs - lastToastMs >= TOAST_INTERVAL_MS) {
            lastToastMs = nowMs
            val driftMs = filteredDriftUs / 1_000.0
            val correctionPercent = (targetSpeed - 1f) * 100f
            Handler(appContext.mainLooper).post {
                Toast.makeText(
                    appContext,
                    "A/V補正 ${String.format("%+.2f", correctionPercent)}%  drift ${String.format("%+.0f", driftMs)}ms",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun isPositionDiscontinuity(positionUs: Long, elapsedRealtimeUs: Long): Boolean {
        if (lastMasterPositionUs == C.TIME_UNSET || lastRenderRealtimeUs == C.TIME_UNSET) return false
        val mediaDeltaUs = positionUs - lastMasterPositionUs
        val realtimeDeltaUs = elapsedRealtimeUs - lastRenderRealtimeUs
        if (mediaDeltaUs < -DISCONTINUITY_TOLERANCE_US) return true
        return abs(mediaDeltaUs - realtimeDeltaUs) > DISCONTINUITY_TOLERANCE_US
    }

    private fun resetTracking(audioClock: MediaClock?, nowMs: Long) {
        filterInitialized = false
        filteredDriftUs = 0.0
        outsideDeadbandSinceMs = C.TIME_UNSET
        audioClock?.let { applyAudioSpeed(it, 1f) }
        if (delegate.state != Renderer.STATE_STARTED) {
            warmupUntilMs = nowMs + WARMUP_MS
        }
    }

    private fun applyAudioSpeed(audioClock: MediaClock, speed: Float) {
        if (abs(speed - appliedSpeed) < MIN_SPEED_CHANGE) return
        val current = audioClock.playbackParameters
        audioClock.playbackParameters = PlaybackParameters(speed, current.pitch)
        appliedSpeed = speed
    }

    private companion object {
        const val WARMUP_MS = 3_000L
        const val PERSISTENCE_MS = 1_500L
        const val TOAST_INTERVAL_MS = 8_000L
        const val DRIFT_DEADBAND_US = 35_000.0
        const val CATCH_UP_WINDOW_US = 20_000_000.0
        const val MAX_SPEED_DELTA = 0.02f
        const val MIN_SPEED_CHANGE = 0.0005f
        const val DRIFT_FILTER_ALPHA = 0.02
        const val DISCONTINUITY_TOLERANCE_US = 500_000L
    }
}

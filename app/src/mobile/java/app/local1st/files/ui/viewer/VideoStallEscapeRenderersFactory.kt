package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * Issue #124 diagnostic.
 *
 * Keep Media3 audio rendering and AudioClock untouched. If the audio-backed player clock briefly
 * plateaus and video output stops, enter a short video-only escape window. The first escape frame
 * is released after a clearly abnormal gap. Subsequent frames are force-released at the estimated
 * source frame cadence instead of waiting another full stall threshold, avoiding the slow-motion
 * pattern seen in PR #146. As soon as the master clock resumes, escape mode is abandoned and
 * Media3's normal late-frame drop logic is allowed to restore phase.
 */
@UnstableApi
internal class VideoStallEscapeRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val primaryIndex = out.size
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
        if (primaryIndex >= out.size) return

        out[primaryIndex] = CadencedEscapeVideoRenderer(
            appContext = context,
            mediaCodecSelector = mediaCodecSelector,
            allowedVideoJoiningTimeMs = allowedVideoJoiningTimeMs,
            enableDecoderFallback = enableDecoderFallback,
            eventHandler = eventHandler,
            eventListener = eventListener,
        )
    }
}

@UnstableApi
@Suppress("DEPRECATION")
private class CadencedEscapeVideoRenderer(
    private val appContext: Context,
    mediaCodecSelector: MediaCodecSelector,
    allowedVideoJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    private val eventHandler: Handler,
    eventListener: VideoRendererEventListener,
) : MediaCodecVideoRenderer(
    appContext,
    mediaCodecSelector,
    allowedVideoJoiningTimeMs,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    MAX_DROPPED_FRAMES_TO_NOTIFY,
) {
    private var escapeActive = false
    private var escapeStartedMs = 0L
    private var estimatedFrameDurationUs = DEFAULT_FRAME_DURATION_US
    private var lastForcedEarlyUs = Long.MIN_VALUE
    private var lastDiagnosticMs = Long.MIN_VALUE
    private var forcedFrameCount = 0

    override fun shouldForceReleaseFrame(
        earlyUs: Long,
        elapsedSinceLastReleaseUs: Long,
    ): Boolean {
        if (super.shouldForceReleaseFrame(earlyUs, elapsedSinceLastReleaseUs)) return true

        val nowMs = SystemClock.elapsedRealtime()

        if (escapeActive) {
            val escapeAgeMs = nowMs - escapeStartedMs
            if (
                escapeAgeMs > MAX_ESCAPE_WINDOW_MS ||
                earlyUs < ESCAPE_EXIT_LATE_US ||
                earlyUs > MAX_EARLY_ESCAPE_US
            ) {
                finishEscape(nowMs, earlyUs, "bounds")
                return false
            }

            if (lastForcedEarlyUs != Long.MIN_VALUE) {
                val earlyDeltaUs = earlyUs - lastForcedEarlyUs

                // Right after a forced frame, the next queued frame exposes its PTS spacing while
                // the audio clock is still stalled. Use that delta as the cadence target.
                if (earlyDeltaUs in MIN_FRAME_DURATION_US..MAX_FRAME_DURATION_US) {
                    estimatedFrameDurationUs =
                        ((estimatedFrameDurationUs * 3L) + earlyDeltaUs) / 4L
                } else if (
                    elapsedSinceLastReleaseUs <= NEXT_FRAME_PROBE_WINDOW_US &&
                    earlyDeltaUs <= CLOCK_RESUMED_DELTA_US
                ) {
                    // The next frame did not move further ahead. The master clock has resumed, so
                    // stop forcing immediately and let normal Media3 scheduling/drop logic recover.
                    finishEscape(nowMs, earlyUs, "clock")
                    return false
                }
            }

            val cadenceThresholdUs =
                (estimatedFrameDurationUs - RELEASE_MARGIN_US)
                    .coerceAtLeast(MIN_FORCE_INTERVAL_US)
            if (
                earlyUs >= MIN_EARLY_ESCAPE_US &&
                elapsedSinceLastReleaseUs >= cadenceThresholdUs
            ) {
                lastForcedEarlyUs = earlyUs
                forcedFrameCount += 1
                return true
            }
            return false
        }

        val shouldEnterEscape =
            elapsedSinceLastReleaseUs >= ESCAPE_ENTRY_GAP_US &&
                earlyUs in MIN_EARLY_ESCAPE_US..MAX_EARLY_ESCAPE_US
        if (!shouldEnterEscape) return false

        escapeActive = true
        escapeStartedMs = nowMs
        estimatedFrameDurationUs = DEFAULT_FRAME_DURATION_US
        lastForcedEarlyUs = earlyUs
        forcedFrameCount = 1
        showDiagnostic(
            nowMs,
            "VIDEO escape start gap=${elapsedSinceLastReleaseUs / 1_000}ms early=${earlyUs / 1_000}ms",
        )
        return true
    }

    private fun finishEscape(nowMs: Long, earlyUs: Long, reason: String) {
        if (!escapeActive) return
        val ageMs = nowMs - escapeStartedMs
        showDiagnostic(
            nowMs,
            "VIDEO escape end $reason age=${ageMs}ms frames=$forcedFrameCount early=${earlyUs / 1_000}ms",
        )
        escapeActive = false
        escapeStartedMs = 0L
        estimatedFrameDurationUs = DEFAULT_FRAME_DURATION_US
        lastForcedEarlyUs = Long.MIN_VALUE
        forcedFrameCount = 0
    }

    private fun showDiagnostic(nowMs: Long, message: String) {
        if (nowMs - lastDiagnosticMs < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnosticMs = nowMs
        eventHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50

        // Normal 24/30/60fps cadence should not enter escape mode. ~55ms means at least one
        // clearly missed frame for the formats relevant to #124, while still reacting well before
        // the observed ~200ms visible freeze completes.
        const val ESCAPE_ENTRY_GAP_US = 55_000L
        const val MIN_EARLY_ESCAPE_US = 0L
        const val MAX_EARLY_ESCAPE_US = 250_000L
        const val ESCAPE_EXIT_LATE_US = -12_000L
        const val MAX_ESCAPE_WINDOW_MS = 320L

        const val DEFAULT_FRAME_DURATION_US = 33_367L
        const val MIN_FRAME_DURATION_US = 12_000L
        const val MAX_FRAME_DURATION_US = 50_000L
        const val RELEASE_MARGIN_US = 2_000L
        const val MIN_FORCE_INTERVAL_US = 10_000L
        const val NEXT_FRAME_PROBE_WINDOW_US = 12_000L
        const val CLOCK_RESUMED_DELTA_US = 8_000L

        const val DIAGNOSTIC_INTERVAL_MS = 1_500L
    }
}

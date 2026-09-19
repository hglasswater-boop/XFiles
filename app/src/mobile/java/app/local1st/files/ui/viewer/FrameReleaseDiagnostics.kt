package app.local1st.files.ui.viewer

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.Locale
import kotlin.math.max

@UnstableApi
internal fun installFrameReleaseDiagnostics(
    context: Context,
    player: ExoPlayer,
) {
    player.setVideoFrameMetadataListener(FrameReleaseDiagnostics(context.applicationContext, player))
}

@UnstableApi
private class FrameReleaseDiagnostics(
    private val context: Context,
    private val player: ExoPlayer,
) : VideoFrameMetadataListener {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var frameCount = 0L
    private var gapCount = 0
    private var lastCallbackNs = C.TIME_UNSET
    private var lastReleaseNs = C.TIME_UNSET
    private var lastPresentationUs = C.TIME_UNSET
    private var lastHeartbeatNs = 0L
    private var maxCallbackGapMs = 0.0
    private var maxReleaseGapMs = 0.0
    private var maxPtsGapMs = 0.0

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        val nowNs = System.nanoTime()
        val callbackGapMs = deltaMs(nowNs, lastCallbackNs)
        val releaseGapMs = deltaMs(releaseTimeNs, lastReleaseNs)
        val ptsGapMs = deltaUsToMs(presentationTimeUs, lastPresentationUs)

        // A seek/media transition can jump the PTS by seconds. Reset the baseline instead of
        // reporting that intentional discontinuity as a playback stall.
        val ptsDiscontinuity = ptsGapMs != null && (ptsGapMs <= 0.0 || ptsGapMs >= 1000.0)
        if (ptsDiscontinuity) {
            lastCallbackNs = nowNs
            lastReleaseNs = releaseTimeNs
            lastPresentationUs = presentationTimeUs
            maxCallbackGapMs = 0.0
            maxReleaseGapMs = 0.0
            maxPtsGapMs = 0.0
            return
        }

        frameCount++
        callbackGapMs?.let { maxCallbackGapMs = max(maxCallbackGapMs, it) }
        releaseGapMs?.let { maxReleaseGapMs = max(maxReleaseGapMs, it) }
        ptsGapMs?.let { maxPtsGapMs = max(maxPtsGapMs, it) }

        // ~29.97 fps normally yields ~33.4 ms. 80 ms catches a visible multi-frame pause while
        // leaving enough margin for ordinary scheduling jitter.
        val abnormal = frameCount > WARMUP_FRAMES && listOfNotNull(
            callbackGapMs,
            releaseGapMs,
            ptsGapMs,
        ).any { it >= FRAME_GAP_THRESHOLD_MS }

        if (abnormal) {
            gapCount++
            report(
                title = "FRAME GAP #$gapCount",
                callbackGapMs = callbackGapMs,
                releaseGapMs = releaseGapMs,
                ptsGapMs = ptsGapMs,
            )
            resetMaxima()
            lastHeartbeatNs = nowNs
        } else if (
            frameCount > WARMUP_FRAMES &&
            (lastHeartbeatNs == 0L || nowNs - lastHeartbeatNs >= HEARTBEAT_INTERVAL_NS)
        ) {
            report(
                title = "FRAME DIAG active gaps=$gapCount",
                callbackGapMs = maxCallbackGapMs,
                releaseGapMs = maxReleaseGapMs,
                ptsGapMs = maxPtsGapMs,
            )
            resetMaxima()
            lastHeartbeatNs = nowNs
        }

        lastCallbackNs = nowNs
        lastReleaseNs = releaseTimeNs
        lastPresentationUs = presentationTimeUs
    }

    private fun report(
        title: String,
        callbackGapMs: Double?,
        releaseGapMs: Double?,
        ptsGapMs: Double?,
    ) {
        val timing = String.format(
            Locale.US,
            "%s\ncb=%s rel=%s pts=%s",
            title,
            formatMs(callbackGapMs),
            formatMs(releaseGapMs),
            formatMs(ptsGapMs),
        )
        mainHandler.post {
            val positionMs = runCatching { player.currentPosition }.getOrDefault(-1L)
            val bufferedMs = runCatching {
                (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
            }.getOrDefault(-1L)
            Toast.makeText(
                context,
                "$timing\npos=${positionMs}ms ahead=${bufferedMs}ms",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun resetMaxima() {
        maxCallbackGapMs = 0.0
        maxReleaseGapMs = 0.0
        maxPtsGapMs = 0.0
    }

    private fun deltaMs(currentNs: Long, previousNs: Long): Double? {
        if (currentNs == C.TIME_UNSET || previousNs == C.TIME_UNSET || currentNs <= previousNs) {
            return null
        }
        return (currentNs - previousNs) / 1_000_000.0
    }

    private fun deltaUsToMs(currentUs: Long, previousUs: Long): Double? {
        if (currentUs == C.TIME_UNSET || previousUs == C.TIME_UNSET) return null
        return (currentUs - previousUs) / 1000.0
    }

    private fun formatMs(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1fms", it) } ?: "n/a"

    companion object {
        private const val WARMUP_FRAMES = 30L
        private const val FRAME_GAP_THRESHOLD_MS = 80.0
        private const val HEARTBEAT_INTERVAL_NS = 10_000_000_000L
    }
}

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
 * Keep Media3's normal audio renderer and AudioClock completely intact. Only override the video
 * frame-release decision so a short audio-clock plateau cannot leave the display frozen for ~200ms.
 * Normal scheduling/drop decisions still come from Media3. We intervene only after no video frame
 * has been released for a noticeable interval and the pending frame is at most 250ms early.
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

        out[primaryIndex] = ForceReleaseVideoRenderer(
            context = context,
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
private class ForceReleaseVideoRenderer(
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
    private var lastDiagnosticMs = Long.MIN_VALUE

    override fun shouldForceReleaseFrame(
        earlyUs: Long,
        elapsedSinceLastReleaseUs: Long,
    ): Boolean {
        if (super.shouldForceReleaseFrame(earlyUs, elapsedSinceLastReleaseUs)) return true

        val shouldForce =
            elapsedSinceLastReleaseUs >= FORCE_AFTER_NO_RELEASE_US &&
                earlyUs in 0L..MAX_EARLY_FORCE_RELEASE_US
        if (!shouldForce) return false

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastDiagnosticMs >= DIAGNOSTIC_INTERVAL_MS) {
            lastDiagnosticMs = nowMs
            val gapMs = elapsedSinceLastReleaseUs / 1_000L
            val earlyMs = earlyUs / 1_000L
            eventHandler.post {
                Toast.makeText(
                    appContext,
                    "VIDEO force gap=${gapMs}ms early=${earlyMs}ms",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        return true
    }

    private companion object {
        const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
        const val FORCE_AFTER_NO_RELEASE_US = 80_000L
        const val MAX_EARLY_FORCE_RELEASE_US = 250_000L
        const val DIAGNOSTIC_INTERVAL_MS = 2_000L
    }
}

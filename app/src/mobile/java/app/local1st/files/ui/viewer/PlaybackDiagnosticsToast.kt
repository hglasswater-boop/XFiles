package app.local1st.files.ui.viewer

import android.content.Context
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Temporary diagnostics for issue #124.
 *
 * The problematic pause is too short to reliably catch by eye, so significant Media3 events are
 * surfaced as toasts and kept as cumulative counters. If the visible pause occurs without any of
 * these events, the investigation can move below buffering/drop/underrun handling to frame timing
 * and rendering.
 */
@UnstableApi
internal class PlaybackDiagnosticsToast(
    context: Context,
    private val player: ExoPlayer,
) : AnalyticsListener {
    private val appContext = context.applicationContext
    private var bufferingCount = 0
    private var droppedFrames = 0
    private var audioUnderruns = 0
    private var videoDecoder = "?"
    private var hasReachedReady = false

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        if (state == Player.STATE_READY) {
            hasReachedReady = true
            return
        }
        if (state == Player.STATE_BUFFERING && hasReachedReady) {
            bufferingCount += 1
            show("BUFFERING #$bufferingCount")
        }
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        this.droppedFrames += droppedFrames
        show("DROP +$droppedFrames / total=${this.droppedFrames}")
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        audioUnderruns += 1
        show("AUDIO UNDERRUN #$audioUnderruns feed=${elapsedSinceLastFeedMs}ms")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoder = decoderName
        show("decoder=$decoderName")
    }

    override fun onVideoCodecError(
        eventTime: AnalyticsListener.EventTime,
        videoCodecError: Exception,
    ) {
        show("VIDEO CODEC ERROR ${videoCodecError.javaClass.simpleName}")
    }

    private fun show(event: String) {
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val aheadMs = (player.bufferedPosition - positionMs).coerceAtLeast(0L)
        val message = buildString {
            append("#124 ")
            append(event)
            append("\npos=")
            append(positionMs)
            append("ms ahead=")
            append(aheadMs)
            append("ms")
            append("\nbuf=")
            append(bufferingCount)
            append(" drop=")
            append(droppedFrames)
            append(" audio=")
            append(audioUnderruns)
            append("\n")
            append(videoDecoder)
        }
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
}

@UnstableApi
internal fun ExoPlayer.installIssue124Diagnostics(context: Context): AnalyticsListener =
    PlaybackDiagnosticsToast(context, this).also(::addAnalyticsListener)

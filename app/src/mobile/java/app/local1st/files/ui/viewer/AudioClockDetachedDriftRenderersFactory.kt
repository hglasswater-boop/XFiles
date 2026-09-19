package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.C
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
 * #124 diagnostic based on the only A/B that removed the visible ~200 ms stalls.
 *
 * Audio decoding/output stays enabled, but the audio renderer is prevented from becoming
 * ExoPlayer's master media clock. The original audio renderer clock is still sampled privately so
 * we can detect whether running from ExoPlayer's standalone clock causes sustained A/V drift.
 *
 * The known short audio-clock stalls are deliberately ignored: a warning is shown only when the
 * clock delta moves at least 50 ms away from its post-start baseline and remains there for 2 s.
 */
@UnstableApi
internal class AudioClockDetachedDriftRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

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
            if (renderer.trackType != C.TRACK_TYPE_AUDIO) return@map renderer

            object : ForwardingRenderer(renderer) {
                private var warmupUntilMs = 0L
                private var baselineDeltaUs: Long? = null
                private var outOfRangeSinceMs = 0L
                private var lastWarningAtMs = 0L

                override fun getMediaClock(): MediaClock? = null

                override fun start() {
                    super.start()
                    resetDriftMeasurement()
                }

                override fun resetPosition(
                    positionUs: Long,
                    sampleStreamIsResetToKeyFrame: Boolean,
                ) {
                    super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
                    resetDriftMeasurement()
                }

                override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
                    super.render(positionUs, elapsedRealtimeUs)
                    sampleDrift(positionUs)
                }

                private fun resetDriftMeasurement() {
                    warmupUntilMs = SystemClock.elapsedRealtime() + WARMUP_MS
                    baselineDeltaUs = null
                    outOfRangeSinceMs = 0L
                }

                private fun sampleDrift(standalonePositionUs: Long) {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs < warmupUntilMs) return

                    val audioPositionUs = renderer.mediaClock?.positionUs ?: return
                    val deltaUs = audioPositionUs - standalonePositionUs
                    val baselineUs = baselineDeltaUs
                    if (baselineUs == null) {
                        baselineDeltaUs = deltaUs
                        return
                    }

                    val driftFromBaselineUs = deltaUs - baselineUs
                    if (abs(driftFromBaselineUs) < BASELINE_TRACKING_WINDOW_US) {
                        baselineDeltaUs = baselineUs + (driftFromBaselineUs / BASELINE_SMOOTHING_DIVISOR)
                    }

                    if (abs(driftFromBaselineUs) < WARNING_DRIFT_US) {
                        outOfRangeSinceMs = 0L
                        return
                    }

                    if (outOfRangeSinceMs == 0L) {
                        outOfRangeSinceMs = nowMs
                        return
                    }
                    if (nowMs - outOfRangeSinceMs < WARNING_PERSISTENCE_MS) return
                    if (nowMs - lastWarningAtMs < WARNING_COOLDOWN_MS) return

                    lastWarningAtMs = nowMs
                    val driftMs = driftFromBaselineUs / 1_000L
                    val sign = if (driftMs >= 0L) "+" else ""
                    mainHandler.post {
                        Toast.makeText(
                            appContext,
                            "A/V drift $sign${driftMs}ms",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }.toTypedArray()

    private companion object {
        const val WARMUP_MS = 3_000L
        const val WARNING_DRIFT_US = 50_000L
        const val WARNING_PERSISTENCE_MS = 2_000L
        const val WARNING_COOLDOWN_MS = 10_000L
        const val BASELINE_TRACKING_WINDOW_US = 20_000L
        const val BASELINE_SMOOTHING_DIVISOR = 32L
    }
}

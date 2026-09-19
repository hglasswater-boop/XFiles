package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
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

/**
 * Diagnostic renderer factory for #124.
 *
 * Audio is decoded and played normally, but the audio renderer is prevented from becoming
 * ExoPlayer's master media clock. This lets us verify whether stalls in AudioTrack/AudioSink clock
 * reporting are what hold back otherwise-continuous video frame release.
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
            if (renderer.trackType == C.TRACK_TYPE_AUDIO) {
                object : ForwardingRenderer(renderer) {
                    override fun getMediaClock(): MediaClock? = null
                }
            } else {
                renderer
            }
        }.toTypedArray()
}

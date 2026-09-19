package app.local1st.files.ui.viewer

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * #124 diagnostic: keep Media3's normal audio renderer/media clock path, but resample decoded PCM
 * to 48 kHz before AudioTrack output. The failing sample's AAC audio is 44.1 kHz.
 */
@UnstableApi
internal class Force48kAudioRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        val resampler = SonicAudioProcessor().apply {
            setOutputSampleRateHz(48_000)
        }
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf<AudioProcessor>(resampler))
            .build()
    }
}

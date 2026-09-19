package app.local1st.files.ui.viewer

import android.content.Context
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * #124 diagnostic PlayerView shim.
 *
 * VideoPlayer.kt resolves this same-package type ahead of its imported Media3 PlayerView,
 * keeping the existing Compose/UI control path unchanged while replacing only the video
 * output surface from SurfaceView to TextureView.
 */
@UnstableApi
internal class PlayerView(context: Context) : FrameLayout(context) {
    private val contentFrame = AspectRatioFrameLayout(context).apply {
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    private val textureView = TextureView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateAspectRatio(videoSize)
        }
    }

    var player: Player? = null
        set(value) {
            if (field === value) {
                if (isAttachedToWindow) value?.setVideoTextureView(textureView)
                return
            }
            field?.removeListener(listener)
            field?.clearVideoTextureView(textureView)
            field = value
            value?.addListener(listener)
            value?.let { updateAspectRatio(it.videoSize) }
            if (isAttachedToWindow) value?.setVideoTextureView(textureView)
        }

    init {
        contentFrame.addView(textureView)
        addView(contentFrame)
    }

    // VideoPlayer.kt disables the stock controller. This shim has no stock controller.
    fun setUseController(useController: Boolean) = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player?.setVideoTextureView(textureView)
    }

    override fun onDetachedFromWindow() {
        player?.clearVideoTextureView(textureView)
        super.onDetachedFromWindow()
    }

    private fun updateAspectRatio(videoSize: VideoSize) {
        val width = videoSize.width
        val height = videoSize.height
        contentFrame.setAspectRatio(
            if (width > 0 && height > 0) width * videoSize.pixelWidthHeightRatio / height else 0f,
        )
    }
}

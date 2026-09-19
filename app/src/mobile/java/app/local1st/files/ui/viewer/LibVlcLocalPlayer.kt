package app.local1st.files.ui.viewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.local1st.files.core.fs.XId
import java.io.File
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Local video player backed by libVLC while preserving the ExoPlayer contract expected by the
 * existing XFiles player UI and CastPlayer.
 *
 * [shadowPlayer] owns the Media3 playlist/timeline/events used by Cast handoff and the existing UI.
 * Its volume is forced to zero. Actual local audio/video output, position and A/V synchronization
 * come from libVLC.
 *
 * Local/content media deliberately uses a direct file descriptor, matching the Issue #124 A/B
 * harness that was verified on-device. SMB/root media still uses [relay]'s loopback endpoint so
 * remote reads and seeks remain on XFiles' Rust/random-access backend.
 */
@UnstableApi
internal class LibVlcLocalPlayer(
    context: Context,
    private val shadowPlayer: ExoPlayer,
    private val relay: CastMediaRelay,
) : ExoPlayer by shadowPlayer {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val libVlc = LibVLC(appContext)
    private val vlcPlayer = MediaPlayer(libVlc)

    private var loadedMediaId: String? = null
    private var desiredPlayWhenReady = false
    private var released = false
    private var attachedVideoOutput: Any? = null
    private var activeDescriptor: ParcelFileDescriptor? = null

    init {
        shadowPlayer.volume = 0f
        vlcPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EndReached -> {
                    mainHandler.post {
                        if (released) return@post
                        if (shadowPlayer.hasNextMediaItem()) {
                            seekToNextMediaItem()
                            if (desiredPlayWhenReady) play()
                        }
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "libVLC encountered an error for mediaId=$loadedMediaId")
                }
            }
        }
    }

    override fun prepare() {
        shadowPlayer.prepare()
        ensureMediaLoaded(shadowPlayer.currentPosition.coerceAtLeast(0L), startPlayback = false)
    }

    override fun play() {
        desiredPlayWhenReady = true
        shadowPlayer.play()
        ensureMediaLoaded(shadowPlayer.currentPosition.coerceAtLeast(0L), startPlayback = true)
        if (!vlcPlayer.isPlaying) vlcPlayer.play()
    }

    override fun pause() {
        desiredPlayWhenReady = false
        runCatching { vlcPlayer.pause() }
        shadowPlayer.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun getPlayWhenReady(): Boolean = desiredPlayWhenReady

    override fun isPlaying(): Boolean = !released && vlcPlayer.isPlaying

    override fun getCurrentPosition(): Long = if (loadedMediaId != null) {
        vlcPlayer.time.coerceAtLeast(0L)
    } else {
        shadowPlayer.currentPosition.coerceAtLeast(0L)
    }

    override fun getDuration(): Long {
        val vlcLength = if (loadedMediaId != null) vlcPlayer.length else 0L
        return if (vlcLength > 0L) vlcLength else shadowPlayer.duration
    }

    override fun getBufferedPosition(): Long {
        val duration = duration
        return if (duration != C.TIME_UNSET && duration > 0L) duration else currentPosition
    }

    override fun getTotalBufferedDuration(): Long =
        (bufferedPosition - currentPosition).coerceAtLeast(0L)

    override fun getPlaybackState(): Int {
        if (released) return Player.STATE_IDLE
        val length = vlcPlayer.length
        val time = vlcPlayer.time
        if (loadedMediaId != null && length > 0L && !vlcPlayer.isPlaying && time >= length - 250L) {
            return Player.STATE_ENDED
        }
        if (loadedMediaId != null) return Player.STATE_READY
        return shadowPlayer.playbackState
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        val oldMediaId = shadowPlayer.currentMediaItem?.mediaId
        shadowPlayer.seekTo(mediaItemIndex, positionMs)
        val newMediaId = shadowPlayer.currentMediaItem?.mediaId
        if (newMediaId != oldMediaId || newMediaId != loadedMediaId) {
            ensureMediaLoaded(positionMs.coerceAtLeast(0L), startPlayback = desiredPlayWhenReady)
        } else if (loadedMediaId != null) {
            runCatching { vlcPlayer.time = positionMs.coerceAtLeast(0L) }
        }
    }

    override fun seekToDefaultPosition() {
        seekToDefaultPosition(currentMediaItemIndex)
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        seekTo(mediaItemIndex, 0L)
    }

    override fun seekToNextMediaItem() {
        val next = nextMediaItemIndex
        if (next != C.INDEX_UNSET) seekTo(next, 0L)
    }

    override fun seekToPreviousMediaItem() {
        val previous = previousMediaItemIndex
        if (previous != C.INDEX_UNSET) seekTo(previous, 0L)
    }

    override fun stop() {
        desiredPlayWhenReady = false
        runCatching { vlcPlayer.stop() }
        loadedMediaId = null
        closeActiveDescriptor()
        shadowPlayer.stop()
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { vlcPlayer.setEventListener(null) }
        detachVlcVideoOutput()
        runCatching { vlcPlayer.stop() }
        closeActiveDescriptor()
        runCatching { vlcPlayer.release() }
        runCatching { libVlc.release() }
        runCatching { shadowPlayer.release() }
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceView == null) {
            detachVlcVideoOutput()
            return
        }
        if (attachedVideoOutput === surfaceView) return
        detachVlcVideoOutput()
        runCatching {
            vlcPlayer.vlcVout.setVideoView(surfaceView)
            vlcPlayer.vlcVout.attachViews()
            attachedVideoOutput = surfaceView
        }
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceView == null || attachedVideoOutput === surfaceView) detachVlcVideoOutput()
    }

    override fun setVideoTextureView(textureView: TextureView?) {
        if (textureView == null) {
            detachVlcVideoOutput()
            return
        }
        if (attachedVideoOutput === textureView) return
        detachVlcVideoOutput()
        runCatching {
            vlcPlayer.vlcVout.setVideoView(textureView)
            vlcPlayer.vlcVout.attachViews()
            attachedVideoOutput = textureView
        }
    }

    override fun clearVideoTextureView(textureView: TextureView?) {
        if (textureView == null || attachedVideoOutput === textureView) detachVlcVideoOutput()
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) {
            detachVlcVideoOutput()
            return
        }
        if (attachedVideoOutput === surfaceHolder) return
        detachVlcVideoOutput()
        runCatching {
            vlcPlayer.vlcVout.setVideoSurface(surfaceHolder.surface, surfaceHolder)
            vlcPlayer.vlcVout.attachViews()
            attachedVideoOutput = surfaceHolder
        }
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null || attachedVideoOutput === surfaceHolder) detachVlcVideoOutput()
    }

    override fun setVideoSurface(surface: Surface?) {
        if (surface == null) {
            detachVlcVideoOutput()
            return
        }
        if (attachedVideoOutput === surface) return
        detachVlcVideoOutput()
        runCatching {
            vlcPlayer.vlcVout.setVideoSurface(surface, null)
            vlcPlayer.vlcVout.attachViews()
            attachedVideoOutput = surface
        }
    }

    override fun clearVideoSurface() {
        detachVlcVideoOutput()
    }

    override fun clearVideoSurface(surface: Surface?) {
        if (surface == null || attachedVideoOutput === surface) detachVlcVideoOutput()
    }

    private fun ensureMediaLoaded(startPositionMs: Long, startPlayback: Boolean) {
        if (released) return
        val mediaItem = shadowPlayer.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId

        if (loadedMediaId != mediaId) {
            runCatching { vlcPlayer.stop() }
            closeActiveDescriptor()
            val media = createVlcMedia(mediaItem) ?: return
            media.setHWDecoderEnabled(true, false)
            vlcPlayer.media = media
            media.release()
            loadedMediaId = mediaId
            if (startPlayback) {
                vlcPlayer.play()
                if (startPositionMs > 0L) runCatching { vlcPlayer.time = startPositionMs }
            }
        } else if (startPositionMs > 0L && kotlin.math.abs(vlcPlayer.time - startPositionMs) > 150L) {
            runCatching { vlcPlayer.time = startPositionMs }
        }
    }

    private fun createVlcMedia(mediaItem: MediaItem): Media? {
        val uri = mediaItem.localConfiguration?.uri ?: return null
        return when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: return null
                val descriptor = runCatching {
                    ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                }.getOrNull() ?: return null
                activeDescriptor = descriptor
                Media(libVlc, descriptor.fileDescriptor)
            }
            "content" -> {
                val descriptor = runCatching {
                    appContext.contentResolver.openFileDescriptor(uri, "r")
                }.getOrNull() ?: return null
                activeDescriptor = descriptor
                Media(libVlc, descriptor.fileDescriptor)
            }
            XId.SCHEME_SMB, XId.SCHEME_ROOT -> {
                val relayUri = relay.localUrlFor(mediaItem.mediaId) ?: return null
                Media(libVlc, relayUri)
            }
            else -> {
                val relayUri = relay.localUrlFor(mediaItem.mediaId)
                Media(libVlc, relayUri ?: uri)
            }
        }
    }

    private fun closeActiveDescriptor() {
        activeDescriptor?.let { descriptor -> runCatching { descriptor.close() } }
        activeDescriptor = null
    }

    private fun detachVlcVideoOutput() {
        if (attachedVideoOutput == null) return
        runCatching { vlcPlayer.vlcVout.detachViews() }
        attachedVideoOutput = null
    }

    private companion object {
        const val TAG = "XFilesLibVLC"
    }
}

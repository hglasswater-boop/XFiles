package app.local1st.files.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.priv.PrivilegedAccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Software-decoded poster frame used only after Android's MediaMetadataRetriever fails.
 *
 * Newer LibVLC bindings no longer expose the old VLCUtil.getThumbnail helper, so the fallback
 * renders into a tiny ImageReader surface and captures a decoded frame. This path is intentionally
 * expensive and only runs for videos the platform retriever could not decode.
 */
internal object SoftwareVideoThumbnail {
    private const val SIZE = 256
    private const val FRAME_TO_CAPTURE = 12
    private const val SETUP_TIMEOUT_MS = 2_000L
    private const val FRAME_TIMEOUT_MS = 10_000L

    fun extract(context: Context, data: VideoThumb): Bitmap? {
        // IVLCVout surface operations are main-thread APIs. Coil calls fetchers off-main; if a
        // future caller violates that contract, fail safely instead of deadlocking the UI thread.
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        var descriptor: ParcelFileDescriptor? = null
        var media: Media? = null
        var mediaReleased = false
        var libVlc: LibVLC? = null
        var mediaPlayer: MediaPlayer? = null
        var imageReader: ImageReader? = null
        var callbackThread: HandlerThread? = null
        var viewsAttached = false

        return try {
            libVlc = LibVLC(context.applicationContext, arrayListOf("--quiet"))
            mediaPlayer = MediaPlayer(libVlc)
            media = if (data.privileged) {
                val transport = PrivilegedAccess.fdTransport() ?: return null
                descriptor = transport.openFd(data.path, write = false) ?: return null
                Media(libVlc, descriptor.fileDescriptor)
            } else {
                Media(libVlc, data.path)
            }
            media.setHWDecoderEnabled(false, false)
            media.addOption(":no-audio")

            callbackThread = HandlerThread("video-thumb-vlc").apply { start() }
            val callbackHandler = Handler(callbackThread.looper)
            imageReader = ImageReader.newInstance(
                SIZE,
                SIZE,
                PixelFormat.RGBA_8888,
                3,
            )

            val captured = AtomicReference<Bitmap?>(null)
            val frameCount = AtomicInteger(0)
            val frameLatch = CountDownLatch(1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                try {
                    if (frameCount.incrementAndGet() >= FRAME_TO_CAPTURE && captured.get() == null) {
                        image.toBitmap()?.let { bitmap ->
                            if (captured.compareAndSet(null, bitmap)) {
                                frameLatch.countDown()
                            } else {
                                bitmap.recycle()
                            }
                        }
                    }
                } finally {
                    image.close()
                }
            }, callbackHandler)

            val setupOk = runOnMainBlocking(SETUP_TIMEOUT_MS) {
                val player = mediaPlayer ?: return@runOnMainBlocking
                val source = media ?: return@runOnMainBlocking
                val reader = imageReader ?: return@runOnMainBlocking
                val vout = player.getVLCVout()
                vout.setVideoSurface(reader.surface, null)
                vout.setWindowSize(SIZE, SIZE)
                vout.attachViews()
                viewsAttached = true
                player.media = source
                source.release()
                mediaReleased = true
                player.play()
            }
            if (!setupOk) return null

            frameLatch.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            captured.get()
        } catch (_: Exception) {
            null
        } finally {
            val player = mediaPlayer
            if (player != null) {
                runOnMainBlocking(SETUP_TIMEOUT_MS) {
                    runCatching { player.stop() }
                    if (viewsAttached) runCatching { player.getVLCVout().detachViews() }
                }
            }
            runCatching { imageReader?.setOnImageAvailableListener(null, null) }
            runCatching { imageReader?.close() }
            runCatching { callbackThread?.quitSafely() }
            if (!mediaReleased) runCatching { media?.release() }
            runCatching { descriptor?.close() }
            runCatching { mediaPlayer?.release() }
            runCatching { libVlc?.release() }
        }
    }

    private fun Image.toBitmap(): Bitmap? {
        if (format != PixelFormat.RGBA_8888) return null
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null

        val rowPadding = (rowStride - pixelStride * width).coerceAtLeast(0)
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        return runCatching {
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
            }
        }.getOrElse {
            padded.recycle()
            null
        }
    }

    private fun runOnMainBlocking(timeoutMs: Long, block: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS) && failure.get() == null
    }
}

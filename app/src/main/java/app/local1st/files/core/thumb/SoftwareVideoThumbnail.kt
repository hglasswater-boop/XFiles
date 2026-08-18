package app.local1st.files.core.thumb

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import app.local1st.files.core.fs.priv.PrivilegedAccess
import java.nio.ByteBuffer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.util.VLCUtil

/** Software-decoded poster frame used only after Android's MediaMetadataRetriever fails. */
internal object SoftwareVideoThumbnail {
    private const val SIZE = 256

    fun extract(context: Context, data: VideoThumb): Bitmap? {
        var descriptor: ParcelFileDescriptor? = null
        var media: Media? = null
        var libVlc: LibVLC? = null
        return try {
            libVlc = LibVLC(context, arrayListOf("--quiet"))
            media = if (data.privileged) {
                val transport = PrivilegedAccess.fdTransport() ?: return null
                descriptor = transport.openFd(data.path, write = false) ?: return null
                Media(libVlc, descriptor.fileDescriptor)
            } else {
                Media(libVlc, data.path)
            }
            media.setHWDecoderEnabled(false, false)
            val rgba = VLCUtil.getThumbnail(media, SIZE, SIZE) ?: return null
            if (rgba.size < SIZE * SIZE * 4) return null
            Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also {
                it.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { media?.release() }
            runCatching { descriptor?.close() }
            runCatching { libVlc?.release() }
        }
    }
}

package app.local1st.files.core.thumb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * Places embedded cover/jacket artwork inside a 16:9 video canvas without cropping it.
 *
 * Browser thumbnail slots vary from square to roughly 13:9 and are rendered with Crop so normal
 * video frames stay edge-to-edge. A portrait or square embedded cover would therefore lose its
 * top and bottom. Padding the artwork into a landscape canvas means later UI cropping consumes
 * the side padding first while the cover itself remains visible.
 */
internal fun fitEmbeddedVideoArtwork(src: Bitmap, targetWidth: Int): Bitmap {
    if (src.width <= 0 || src.height <= 0 || targetWidth <= 0) return src

    val targetHeight = (targetWidth * 9f / 16f).roundToInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)

    val scale = minOf(
        targetWidth.toFloat() / src.width,
        targetHeight.toFloat() / src.height,
    )
    val drawWidth = src.width * scale
    val drawHeight = src.height * scale
    val left = (targetWidth - drawWidth) / 2f
    val top = (targetHeight - drawHeight) / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    canvas.drawBitmap(
        src,
        null,
        RectF(left, top, left + drawWidth, top + drawHeight),
        paint,
    )
    if (out !== src) src.recycle()
    return out
}

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

/**
 * Lightweight luma sampling used to avoid persisting black title cards, broken decoder output,
 * or effectively empty embedded artwork as a video's representative thumbnail.
 */
internal fun videoThumbnailBrightnessScore(bitmap: Bitmap): Double {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 0.0
    val stepX = (bitmap.width / 12).coerceAtLeast(1)
    val stepY = (bitmap.height / 12).coerceAtLeast(1)
    var sum = 0.0
    var count = 0
    var y = stepY / 2
    while (y < bitmap.height) {
        var x = stepX / 2
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val luma = 0.2126 * Color.red(pixel) +
                0.7152 * Color.green(pixel) +
                0.0722 * Color.blue(pixel)
            sum += luma
            count++
            x += stepX
        }
        y += stepY
    }
    return if (count > 0) sum / count else 0.0
}

internal fun isNearlyBlackVideoThumbnail(bitmap: Bitmap): Boolean {
    if (bitmap.width <= 0 || bitmap.height <= 0) return true

    // Sample a little more densely than the ranking score. The old test only rejected frames
    // with average luma < 22 and < 5% lit pixels, which allowed mostly-black title cards with a
    // small logo/text patch to count as usable. Track both the dark area and genuinely visible
    // area so black-heavy frames are rejected without treating normal letterboxing as black.
    val stepX = (bitmap.width / 16).coerceAtLeast(1)
    val stepY = (bitmap.height / 16).coerceAtLeast(1)
    var sum = 0.0
    var count = 0
    var dark = 0
    var visiblyLit = 0
    var y = stepY / 2
    while (y < bitmap.height) {
        var x = stepX / 2
        while (x < bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            val luma = 0.2126 * Color.red(pixel) +
                0.7152 * Color.green(pixel) +
                0.0722 * Color.blue(pixel)
            sum += luma
            if (luma < 32.0) dark++
            if (luma >= 55.0) visiblyLit++
            count++
            x += stepX
        }
        y += stepY
    }
    if (count == 0) return true

    val average = sum / count
    val darkFraction = dark.toDouble() / count
    val litFraction = visiblyLit.toDouble() / count

    return (average < 36.0 && litFraction < 0.18) ||
        (darkFraction > 0.82 && average < 48.0 && litFraction < 0.25)
}

package app.local1st.files.ui.viewer

import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import java.util.Locale

/** Only concrete photo MIME types supported by the default Cast Web Receiver. */
internal fun castImageMimeType(entry: XEntry): String? {
    val fromExtension = when (entry.extension.lowercase(Locale.US)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "apng" -> "image/apng"
        else -> null
    }
    if (fromExtension != null) return fromExtension

    return entry.mime?.substringBefore(';')?.trim()?.lowercase(Locale.US)?.takeIf {
        it in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/apng")
    }
}

/** These sources are understood by CastMediaRelay, unlike archive members. */
internal fun canCastRelayImage(entry: XEntry): Boolean =
    !entry.isDir && (
        entry.localPath != null ||
            entry.scheme == "content" ||
            entry.scheme == XId.SCHEME_SMB ||
            entry.scheme == XId.SCHEME_ROOT
        )

/** Swipes select a new image; a newly connected Cast receiver must also receive the current one. */
internal fun shouldSendCastImage(
    previousImageId: String?,
    currentImageId: String,
    sessionChanged: Boolean,
): Boolean = sessionChanged || previousImageId != currentImageId

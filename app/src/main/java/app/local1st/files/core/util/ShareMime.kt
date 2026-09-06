package app.local1st.files.core.util

/**
 * Returns the narrowest safe MIME type for a multi-file share intent.
 * Keeping homogeneous video shares as video/* lets video-only targets appear in Android's chooser.
 */
internal fun commonShareMimeType(mimeTypes: List<String?>): String {
    if (mimeTypes.isEmpty()) return "*/*"
    val resolved = mimeTypes.map { it?.takeIf(String::isNotBlank) ?: "*/*" }
    val exact = resolved.distinct()
    if (exact.size == 1) return exact.single()

    val topLevels = resolved.map { it.substringBefore('/', missingDelimiterValue = "*") }.distinct()
    return if (topLevels.size == 1 && topLevels.single() != "*") {
        "${topLevels.single()}/*"
    } else {
        "*/*"
    }
}

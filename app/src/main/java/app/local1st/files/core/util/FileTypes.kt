package app.local1st.files.core.util

import android.webkit.MimeTypeMap

/** Broad category used for icons, viewers and thumbnails. */
enum class FileCategory { IMAGE, VIDEO, AUDIO, TEXT, PDF, ARCHIVE, APK, DATABASE, GENERIC }

object FileTypes {

    /**
     * Split-APK bundles: a zip holding base + every split. One format under three names —
     * `.apks` from bundletool/SAI, `.apkm` from APKMirror, `.xapk` from APKPure — so we take
     * all three and install them the same way, rather than making people rename the file.
     */
    val apkBundleExtensions = setOf("apks", "apkm", "xapk")

    val archiveExtensions =
        setOf(
            "zip", "jar", "apk", "aab", "7z", "tar", "gz", "tgz", "bz2", "tbz2",
            "xz", "txz", "rar",
        ) +
            apkBundleExtensions

    private val textExtensions = setOf(
        "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "kts", "java",
        "py", "c", "h", "cpp", "hpp", "rs", "go", "sh", "zsh", "bash", "yaml", "yml", "toml",
        "ini", "conf", "properties", "log", "csv", "gradle", "pro", "sql", "srt",
    )

    // Keep the thumbnail path independent of a device/vendor MimeTypeMap being incomplete.
    // Network filesystems commonly provide no MIME at all, so extension fallback is essential.
    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff",
    )
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "avi", "mov", "webm", "mpg", "mpeg", "ts", "m2ts", "mts",
        "3gp", "3g2", "flv", "wmv", "vob",
    )
    private val audioExtensions = setOf(
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "amr",
    )

    fun mimeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        if (ext == "aab" || ext in apkBundleExtensions) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                in imageExtensions -> when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    else -> "image/*"
                }
                in videoExtensions -> when (ext) {
                    "mp4", "m4v" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    else -> "video/*"
                }
                in audioExtensions -> "audio/*"
                in textExtensions -> "text/plain"
                "apk" -> "application/vnd.android.package-archive"
                "7z" -> "application/x-7z-compressed"
                "rar" -> "application/vnd.rar"
                else -> null
            }
    }

    fun categoryOf(name: String, mime: String? = null): FileCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        val resolvedMime = mime?.takeIf { it.isNotBlank() } ?: mimeOf(name)
        return when {
            ext == "apk" || ext == "aab" || ext in apkBundleExtensions -> FileCategory.APK
            ext == "db" || ext == "sqlite" || ext == "sqlite3" -> FileCategory.DATABASE
            ext in archiveExtensions -> FileCategory.ARCHIVE
            ext in imageExtensions -> FileCategory.IMAGE
            ext in videoExtensions -> FileCategory.VIDEO
            ext in audioExtensions -> FileCategory.AUDIO
            resolvedMime == null -> if (ext in textExtensions) FileCategory.TEXT else FileCategory.GENERIC
            resolvedMime.startsWith("image/") -> FileCategory.IMAGE
            resolvedMime.startsWith("video/") -> FileCategory.VIDEO
            resolvedMime.startsWith("audio/") -> FileCategory.AUDIO
            resolvedMime == "application/pdf" -> FileCategory.PDF
            resolvedMime.startsWith("text/") -> FileCategory.TEXT
            else -> FileCategory.GENERIC
        }
    }

    /** Archives we can browse into as folders. */
    fun isBrowsableArchive(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in archiveExtensions
    }

    /** Every archive or compressed-stream format backed by the archive file system. */
    fun isSupportedArchive(name: String): Boolean = isBrowsableArchive(name)

    /** True for package files supported by the direct, split-bundle, or AAB install routes. */
    fun isInstallable(extension: String): Boolean =
        extension == "apk" || extension == "aab" || extension in apkBundleExtensions
}

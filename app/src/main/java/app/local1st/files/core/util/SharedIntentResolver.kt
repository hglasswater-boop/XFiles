package app.local1st.files.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Converts Android share intents into ordinary local entries that the existing copy pipeline can use. */
object SharedIntentResolver {
    fun resolve(context: Context, intent: Intent): List<XEntry> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            throw IOException("Unsupported share action")
        }
        val uris = sharedUris(intent)
        if (uris.isEmpty()) throw IOException("No shared file was provided")

        val cacheDir = File(context.cacheDir, CACHE_DIRECTORY)
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            throw IOException("Cannot create shared-file cache")
        }
        pruneOldCache(cacheDir)

        val explicitMime = intent.type?.takeUnless { '*' in it }
        return uris.map { uri -> materialize(context, cacheDir, uri, explicitMime) }
    }

    private fun materialize(
        context: Context,
        cacheDir: File,
        uri: Uri,
        explicitMime: String?,
    ): XEntry {
        if (uri.scheme == "file") {
            val path = uri.path ?: throw IOException("Invalid file URI")
            val file = File(path)
            if (!file.isFile) throw IOException("Cannot read shared file")
            return XEntry(
                id = XId.file(file.absolutePath),
                name = file.name,
                isDir = false,
                size = file.length(),
                mime = explicitMime ?: FileTypes.mimeOf(file.name),
                canWrite = file.canWrite(),
                kind = EntryKind.FILE,
                localPath = file.absolutePath,
            )
        }

        val metadata = metadata(context, uri, explicitMime)
        val safeName = metadata.name
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .takeLast(160)
            .ifBlank { "shared-file" }
        val cached = File(cacheDir, "${UUID.randomUUID()}-$safeName")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot read ${metadata.name}")
            input.use { source -> FileOutputStream(cached).buffered().use(source::copyTo) }
        } catch (error: Exception) {
            cached.delete()
            throw error
        }

        return XEntry(
            id = XId.file(cached.absolutePath),
            name = metadata.name,
            isDir = false,
            size = cached.length(),
            mime = metadata.mime,
            canWrite = false,
            kind = EntryKind.FILE,
            localPath = cached.absolutePath,
        )
    }

    private data class Metadata(
        val name: String,
        val size: Long,
        val mime: String?,
    )

    private fun metadata(context: Context, uri: Uri, explicitMime: String?): Metadata {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared-file"
        return Metadata(
            name = resolvedName,
            size = size,
            mime = explicitMime
                ?: context.contentResolver.getType(uri)
                ?: FileTypes.mimeOf(resolvedName),
        )
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val result = LinkedHashSet<Uri>()
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(result::add)
            }
        }

        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(result::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.forEach(result::add)
            }
        }
        return result.toList()
    }

    private fun pruneOldCache(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MILLIS
        cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    private const val CACHE_DIRECTORY = "shared-files"
    private const val CACHE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L
}

package app.local1st.files.core.util

import android.content.Context
import android.os.Build
import android.os.Environment
import app.local1st.files.vendor.arsclib.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.json.JSONObject

object XapkObbInstaller {

    open class ObbPlacementException(message: String, cause: Throwable? = null) : IOException(message, cause)

    class UnknownSourcesPermissionException(message: String) : ObbPlacementException(message)

    class ObbEntry internal constructor(
        internal val source: ZipEntry,
        internal val relativeDestination: String,
    )

    class Placement internal constructor(
        private val writtenFiles: List<File>,
        private val backups: List<Pair<File, File>>,
    ) {
        fun cleanUp() {
            writtenFiles.forEach(File::delete)
            backups.forEach { (destination, backup) ->
                runCatching {
                    Files.move(backup.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        fun commit() {
            backups.forEach { (_, backup) -> backup.delete() }
        }
    }

    fun findObbs(zip: ZipFile): List<ObbEntry> {
        val byName = buildMap<String, ZipEntry> {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) put(normalizeZipPath(entry.name).lowercase(Locale.ROOT), entry)
            }
        }
        val packageName = installedPackageName(zip, byName.values)
        val fromManifest: List<ObbEntry>? = runCatching<List<ObbEntry>?> {
            val manifest = byName.entries.firstOrNull {
                !it.key.contains('/') && it.key.equals("manifest.json", ignoreCase = true)
            }?.value ?: return@runCatching null
            val json = zip.getInputStream(manifest).bufferedReader().use { JSONObject(it.readText()) }
            json.optString("package_name").takeIf { it.isNotBlank() }?.let { declared ->
                if (declared != packageName) {
                    throw ObbPlacementException(
                        "XAPK manifest package $declared does not match base APK package $packageName",
                    )
                }
            }
            val expansions = json.optJSONArray("expansions") ?: return@runCatching null
            buildList<ObbEntry> {
                for (i in 0 until expansions.length()) {
                    val item = expansions.optJSONObject(i)
                        ?: throw ObbPlacementException("Invalid XAPK expansion at index $i")
                    val sourcePath = normalizeZipPath(item.optString("file"))
                    val source = byName[sourcePath.lowercase(Locale.ROOT)]
                    val destination = listOf("install_path", "install_location")
                        .asSequence()
                        .map { item.optString(it) }
                        .mapNotNull(::obbRelativePath)
                        .firstOrNull()
                        ?: obbRelativePath(sourcePath)
                        ?: throw ObbPlacementException("Cannot map XAPK expansion '$sourcePath' to Android/obb")
                    requireDestinationPackage(destination, packageName)
                    if (source == null) {
                        val file = File(Environment.getExternalStorageDirectory(), destination)
                        if (!destinationExists(file)) {
                            throw ObbPlacementException("XAPK expansion '$sourcePath' is missing")
                        }
                        continue
                    }
                    add(ObbEntry(source, destination))
                }
            }
        }.getOrElse { error ->
            if (error is ObbPlacementException) throw error
            throw ObbPlacementException("Cannot read XAPK manifest: ${error.message ?: "invalid JSON"}", error)
        }
        if (fromManifest != null) return fromManifest.distinctBy { it.relativeDestination }

        return byName.mapNotNull { (path, entry) ->
            obbRelativePath(path)?.also { requireDestinationPackage(it, packageName) }
                ?.let { ObbEntry(entry, it) }
        }.distinctBy { it.relativeDestination }
    }

    fun place(
        context: Context,
        zip: ZipFile,
        obbs: List<ObbEntry>,
        progress: InstallProgress = InstallProgress(),
    ): Placement {
        if (obbs.isNotEmpty() && !canRequestPackageInstalls(context)) {
            throw UnknownSourcesPermissionException(
                "Enable ‘Install unknown apps’ for XFiles, then retry. The permission cache may also require an XFiles restart.",
            )
        }
        if (obbs.isNotEmpty()) progress.onPhase(InstallPhase.EXTRACTING_OBB)
        val externalRoot = Environment.getExternalStorageDirectory().canonicalFile
        val written = mutableListOf<File>()
        val backups = mutableListOf<Pair<File, File>>()
        // Expansion files run to gigabytes, so report one bar across the whole set.
        val totalBytes = obbs.sumOf { it.source.size.coerceAtLeast(0) }
        var doneBytes = 0L
        try {
            obbs.forEach { obb ->
                val startedAtBytes = doneBytes
                doneBytes += obb.source.size.coerceAtLeast(0)
                val destination = File(externalRoot, obb.relativeDestination).canonicalFile
                if (!destination.path.startsWith(externalRoot.path + File.separator)) {
                    throw ObbPlacementException("Invalid OBB install path")
                }
                val existingSize = destinationSize(destination)
                if (existingSize == obb.source.size && existingSize >= 0) {
                    progress.onBytes(doneBytes, totalBytes)
                    return@forEach
                }
                if (existingSize >= 0) {
                    backups += destination to backUp(destination)
                }
                try {
                    zip.getInputStream(obb.source).use { input ->
                        writeDirect(input, destination, progress, startedAtBytes, totalBytes)
                    }
                    written += destination
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val cause = e.message ?: e.javaClass.simpleName ?: "write failed"
                    throw ObbPlacementException(
                        if (canRequestPackageInstalls(context)) {
                            "Couldn't write ${destination.name}: $cause. The ‘Install unknown apps’ grant may be too recent; restart XFiles and retry."
                        } else {
                            "Couldn't write ${destination.name}: $cause. Enable ‘Install unknown apps’ for XFiles, restart XFiles if needed, then retry."
                        },
                        e,
                    )
                }
            }
            return Placement(written, backups)
        } catch (e: Exception) {
            Placement(written, backups).cleanUp()
            if (e is CancellationException) throw e
            if (e is ObbPlacementException) throw e
            throw ObbPlacementException("Couldn't place OBB: ${e.message ?: "write failed"}", e)
        }
    }

    private fun writeDirect(
        input: java.io.InputStream,
        destination: File,
        progress: InstallProgress,
        doneBytes: Long,
        totalBytes: Long,
    ) {
        val parent = destination.parentFile ?: throw IOException("Invalid OBB destination")
        val temp = File(parent, ".${destination.name}.xfiles-${UUID.randomUUID()}.part")
        try {
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create ${parent.path}")
            FileOutputStream(temp).use { output ->
                copyWithProgress(input, output, progress, doneBytes, totalBytes)
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun backUp(destination: File): File {
        val parent = destination.parentFile ?: throw ObbPlacementException("Invalid OBB destination")
        val backup = File(parent, ".${destination.name}.xfiles-backup-${UUID.randomUUID()}")
        return try {
            Files.move(destination.toPath(), backup.toPath())
            backup
        } catch (e: Exception) {
            throw ObbPlacementException("Couldn't back up ${destination.name}", e)
        }
    }

    private fun destinationSize(file: File): Long = if (file.isFile) file.length() else -1

    private fun destinationExists(file: File): Boolean = destinationSize(file) >= 0

    private fun normalizeZipPath(path: String): String = path.replace('\\', '/').trimStart('/')

    private fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun installedPackageName(zip: ZipFile, entries: Collection<ZipEntry>): String {
        val basePackages = entries.asSequence()
            .filter { it.name.substringAfterLast('/').endsWith(".apk", ignoreCase = true) }
            .mapNotNull { apk ->
                runCatching {
                    zip.getInputStream(apk).use { apkInput ->
                        ZipInputStream(apkInput).use { inner ->
                            generateSequence { inner.nextEntry }
                                .firstOrNull { normalizeZipPath(it.name) == "AndroidManifest.xml" }
                                ?.let { AndroidManifestBlock.load(inner) }
                        }
                    }
                }.getOrNull()
            }
            .filter { !it.isSplit }
            .mapNotNull { it.packageName?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        if (basePackages.size != 1) {
            throw ObbPlacementException("XAPK must contain exactly one readable base APK")
        }
        return basePackages.single()
    }

    private fun requireDestinationPackage(destination: String, packageName: String) {
        val destinationPackage = normalizeZipPath(destination).split('/').getOrNull(2)
        if (destinationPackage != packageName) {
            throw ObbPlacementException(
                "OBB package '${destinationPackage ?: "missing"}' does not match base APK package $packageName",
            )
        }
    }

    private fun obbRelativePath(path: String): String? {
        val normalized = normalizeZipPath(path)
        val marker = "android/obb/"
        val start = normalized.lowercase().indexOf(marker)
        if (start < 0) return null
        val suffix = normalized.substring(start + marker.length)
        if (suffix.isBlank() || suffix.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        return "Android/obb/$suffix"
    }
}

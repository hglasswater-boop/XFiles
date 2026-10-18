package app.local1st.files.core.ops

import app.local1st.files.core.util.FileTypes
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Publishes completed media files in shared storage to Android's media scanner.
 *
 * The scanner callback is injected by the Android composition root; all eligibility rules
 * remain platform-independent and testable. Indexing is best effort and must not fail a copy.
 */
class LocalMediaIndexer(
    sharedStorageRoots: List<File>,
    private val scan: (String) -> Unit,
) {
    private val roots = sharedStorageRoots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinct()

    /** Must be called only after the destination output stream was successfully closed. */
    fun publish(file: File) {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!target.isFile || !FileTypes.isMediaFileName(target.name)) return

        val root = roots.firstOrNull { target != it && target.toPath().startsWith(it.toPath()) }
            ?: return
        val parts = target.relativeTo(root).invariantSeparatorsPath.split('/')
        if (parts.any { it.startsWith('.') }) return
        if (parts.size >= 3 && parts[0].equals("Android", ignoreCase = true) &&
            (parts[1].equals("data", ignoreCase = true) ||
                parts[1].equals("obb", ignoreCase = true))
        ) return

        var parent = target.parentFile
        while (parent != null) {
            if (File(parent, ".nomedia").exists()) return
            if (parent == root) break
            parent = parent.parentFile
        }

        // MediaScannerConnection.scanFile is asynchronous. Its failure must never retry writes.
        runCatching { scan(target.absolutePath) }
    }

    /** Re-index files after a successful local directory rename without following symlinked dirs. */
    fun publishTree(target: File) {
        if (target.isFile) {
            publish(target)
            return
        }
        if (!target.isDirectory) return
        runCatching {
            Files.walkFileTree(
                target.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) publish(file.toFile())
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}

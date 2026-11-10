package app.local1st.files.core.fs

import java.io.IOException
import java.util.ArrayDeque

internal data class SmbDeleteDirectoryEntry(
    val name: String,
    val isDirectory: Boolean,
    val isReparsePoint: Boolean = false,
)

/** Only the SMB operations needed by the non-recursive, postorder directory walker. */
internal interface SmbDirectoryDeleteOperations {
    fun list(path: String): List<SmbDeleteDirectoryEntry>
    fun deleteFile(path: String)
    fun deleteDirectory(path: String)
}

private enum class DeleteAction { LIST_DIRECTORY, DELETE_DIRECTORY, DELETE_FILE }
private data class PendingDelete(val path: String, val action: DeleteAction)

/**
 * Delete a directory's contents before the directory itself.
 *
 * Every child file goes through the caller's deletion policy (including read-only recovery);
 * directory reparse points are removed as entries, never followed. Errors abort immediately.
 */
internal fun deleteSmbDirectoryRecursively(
    rootPath: String,
    operations: SmbDirectoryDeleteOperations,
) {
    if (rootPath.trim('\\', '/').isBlank()) {
        throw IOException("Refusing to delete SMB share root")
    }

    val pending = ArrayDeque<PendingDelete>()
    pending.addLast(PendingDelete(rootPath, DeleteAction.LIST_DIRECTORY))

    while (pending.isNotEmpty()) {
        val task = pending.removeLast()
        when (task.action) {
            DeleteAction.DELETE_FILE -> operations.deleteFile(task.path)
            DeleteAction.DELETE_DIRECTORY -> operations.deleteDirectory(task.path)
            DeleteAction.LIST_DIRECTORY -> {
                // Validate the entire listing before deleting any of its entries.
                val children = operations.list(task.path).filterNot {
                    it.name == "." || it.name == ".."
                }
                children.forEach { requireSafeEntryName(it.name) }

                pending.addLast(PendingDelete(task.path, DeleteAction.DELETE_DIRECTORY))
                for (child in children.asReversed()) {
                    val childPath = "${task.path}\\${child.name}"
                    val action = when {
                        !child.isDirectory -> DeleteAction.DELETE_FILE
                        child.isReparsePoint -> DeleteAction.DELETE_DIRECTORY
                        else -> DeleteAction.LIST_DIRECTORY
                    }
                    pending.addLast(PendingDelete(childPath, action))
                }
            }
        }
    }
}

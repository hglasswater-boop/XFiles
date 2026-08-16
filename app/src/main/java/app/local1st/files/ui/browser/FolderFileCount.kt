package app.local1st.files.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Direct child counts for a folder. String form is used by the existing row details formatter. */
internal data class FolderDirectCounts(
    val folders: Int,
    val files: Int,
) {
    override fun toString(): String = "$folders フォルダ · $files"
}

/**
 * Counts direct folders and files for a visible ordinary folder. Counts are lazy and bounded so a
 * directory full of subfolders does not turn into an SMB request storm just because its parent was
 * opened. This is intentionally non-recursive.
 */
@Composable
internal fun rememberFolderFileCount(entry: XEntry): FolderDirectCounts? {
    if (entry.kind != EntryKind.DIR || isSmbConnectionRoot(entry)) return null
    val key = "${entry.id}|${entry.mtime}"
    val count by produceState<FolderDirectCounts?>(FolderFileCountCache.peek(key), key) {
        if (value == null) value = FolderFileCountCache.load(key, entry)
    }
    return count
}

private fun isSmbConnectionRoot(entry: XEntry): Boolean =
    entry.scheme == XId.SCHEME_SMB && entry.path.isNotBlank() && !entry.path.contains('/')

private object FolderFileCountCache {
    private const val MAX_ENTRIES = 2048
    private val counts = ConcurrentHashMap<String, FolderDirectCounts>()
    private val reads = Semaphore(4)

    fun peek(key: String): FolderDirectCounts? = counts[key]

    suspend fun load(key: String, entry: XEntry): FolderDirectCounts? = reads.withPermit {
        counts[key]?.let { return@withPermit it }
        val count = withContext(Dispatchers.IO) {
            runCatching {
                var folders = 0
                var files = 0
                Graph.fsRegistry.forEntry(entry).list(entry).forEach { child ->
                    if (child.isDir) folders++ else files++
                }
                FolderDirectCounts(folders = folders, files = files)
            }.getOrNull()
        } ?: return@withPermit null
        if (counts.size >= MAX_ENTRIES) counts.clear()
        counts[key] = count
        count
    }
}

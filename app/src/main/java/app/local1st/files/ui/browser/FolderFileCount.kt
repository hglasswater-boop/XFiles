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

/**
 * Counts direct files for a visible ordinary folder. Counts are lazy and bounded so a directory
 * full of subfolders does not turn into an SMB request storm just because its parent was opened.
 */
@Composable
internal fun rememberFolderFileCount(entry: XEntry): Int? {
    if (entry.kind != EntryKind.DIR || isSmbConnectionRoot(entry)) return null
    val key = "${entry.id}|${entry.mtime}"
    val count by produceState<Int?>(FolderFileCountCache.peek(key), key) {
        if (value == null) value = FolderFileCountCache.load(key, entry)
    }
    return count
}

private fun isSmbConnectionRoot(entry: XEntry): Boolean =
    entry.scheme == XId.SCHEME_SMB && entry.path.isNotBlank() && !entry.path.contains('/')

private object FolderFileCountCache {
    private const val MAX_ENTRIES = 2048
    private val counts = ConcurrentHashMap<String, Int>()
    private val reads = Semaphore(4)

    fun peek(key: String): Int? = counts[key]

    suspend fun load(key: String, entry: XEntry): Int? = reads.withPermit {
        counts[key]?.let { return@withPermit it }
        val count = withContext(Dispatchers.IO) {
            runCatching {
                Graph.fsRegistry.forEntry(entry).list(entry).count { !it.isDir }
            }.getOrNull()
        } ?: return@withPermit null
        if (counts.size >= MAX_ENTRIES) counts.clear()
        counts[key] = count
        count
    }
}

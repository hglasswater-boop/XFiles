package app.local1st.files.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Direct child folder/file counts for a directory. */
internal data class FolderDirectCounts(
    val folders: Int,
    val files: Int,
)

/**
 * Counts direct folders and files for a visible ordinary folder. Counts are lazy and bounded so a
 * directory full of subfolders does not turn into an SMB request storm just because its parent was
 * opened. This is intentionally non-recursive.
 */
@Composable
internal fun rememberFolderFileCount(entry: XEntry): FolderDirectCounts? {
    if (entry.kind != EntryKind.DIR || isSmbConnectionRoot(entry)) return null
    FolderFileCountCache.ensureInvalidationCollector()
    val key = "${entry.id}|${entry.mtime}"
    val count by produceState<FolderDirectCounts?>(FolderFileCountCache.peek(key), key) {
        if (value == null) value = FolderFileCountCache.load(key, entry)
        Graph.opEngine.events.collect { event ->
            if (entry.id in event.dirtyDirIds) {
                value = FolderFileCountCache.load(key, entry, force = true)
            }
        }
    }
    return count
}

private fun isSmbConnectionRoot(entry: XEntry): Boolean =
    entry.scheme == XId.SCHEME_SMB && entry.path.isNotBlank() && !entry.path.contains('/')

private object FolderFileCountCache {
    private const val MAX_ENTRIES = 2048
    private val counts = ConcurrentHashMap<String, FolderDirectCounts>()
    private val reads = Semaphore(4)
    private val invalidationStarted = AtomicBoolean(false)

    fun ensureInvalidationCollector() {
        if (!invalidationStarted.compareAndSet(false, true)) return
        Graph.appScope.launch {
            Graph.opEngine.events.collect { event ->
                event.dirtyDirIds.forEach(::invalidate)
            }
        }
    }

    private fun invalidate(entryId: String) {
        val prefix = "$entryId|"
        counts.keys.forEach { key ->
            if (key.startsWith(prefix)) counts.remove(key)
        }
    }

    fun peek(key: String): FolderDirectCounts? = counts[key]

    suspend fun load(
        key: String,
        entry: XEntry,
        force: Boolean = false,
    ): FolderDirectCounts? = reads.withPermit {
        if (!force) counts[key]?.let { return@withPermit it }
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

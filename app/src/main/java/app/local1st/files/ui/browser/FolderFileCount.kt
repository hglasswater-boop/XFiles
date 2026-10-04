package app.local1st.files.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Direct child folder/file counts for a directory. */
internal data class FolderDirectCounts(
    val folders: Int,
    val files: Int,
)

/** A targeted cache update for one directory row. Null means the cached count is stale. */
internal data class FolderFileCountUpdate(
    val directoryId: String,
    val counts: FolderDirectCounts?,
)

/** Small observable bounded cache state kept separate from Compose so refresh behavior is testable. */
internal class FolderFileCountState(
    private val maxEntries: Int = 2048,
) {
    private val counts = ConcurrentHashMap<String, FolderDirectCounts>()
    private val mutableUpdates = MutableSharedFlow<FolderFileCountUpdate>(extraBufferCapacity = 64)

    val updates: SharedFlow<FolderFileCountUpdate> = mutableUpdates.asSharedFlow()

    fun peek(key: String): FolderDirectCounts? = counts[key]

    fun publish(key: String, directoryId: String, counts: FolderDirectCounts) {
        if (this.counts.size >= maxEntries && !this.counts.containsKey(key)) this.counts.clear()
        this.counts[key] = counts
        mutableUpdates.tryEmit(FolderFileCountUpdate(directoryId = directoryId, counts = counts))
    }

    fun invalidate(directoryId: String) {
        val prefix = "$directoryId|"
        counts.keys.forEach { key ->
            if (key.startsWith(prefix)) counts.remove(key)
        }
        mutableUpdates.tryEmit(FolderFileCountUpdate(directoryId = directoryId, counts = null))
    }
}

/**
 * Counts direct folders and files for a visible ordinary folder. Counts are lazy and bounded so a
 * directory full of subfolders does not turn into an SMB request storm just because its parent was
 * opened. This is intentionally non-recursive.
 */
@Composable
internal fun rememberFolderFileCount(entry: XEntry): FolderDirectCounts? {
    if (!supportsFolderFileCount(entry)) return null
    val key = folderFileCountKey(entry)
    val count by produceState<FolderDirectCounts?>(FolderFileCountCache.peek(key), key) {
        if (value == null) value = FolderFileCountCache.load(key, entry)
        FolderFileCountCache.updates.collect { update ->
            if (update.directoryId != entry.id) return@collect
            value = update.counts ?: FolderFileCountCache.load(key, entry, force = true)
        }
    }
    return count
}

/** Publishes a successful browser listing as the authoritative direct folder/file count. */
internal fun publishFolderFileCount(entry: XEntry, children: List<XEntry>) {
    if (!supportsFolderFileCount(entry)) return
    var folders = 0
    var files = 0
    children.forEach { child ->
        if (child.isDir) folders++ else files++
    }
    FolderFileCountCache.publish(
        key = folderFileCountKey(entry),
        directoryId = entry.id,
        counts = FolderDirectCounts(folders = folders, files = files),
    )
}

/** Marks one directory count stale without touching unrelated rows. */
internal fun invalidateFolderFileCount(directoryId: String) {
    FolderFileCountCache.invalidate(directoryId)
}

private fun supportsFolderFileCount(entry: XEntry): Boolean =
    entry.kind == EntryKind.DIR && !isSmbConnectionRoot(entry)

private fun folderFileCountKey(entry: XEntry): String = "${entry.id}|${entry.mtime}"

private fun isSmbConnectionRoot(entry: XEntry): Boolean =
    entry.scheme == XId.SCHEME_SMB && entry.path.isNotBlank() && !entry.path.contains('/')

private object FolderFileCountCache {
    private const val MAX_ENTRIES = 2048
    private val state = FolderFileCountState(MAX_ENTRIES)

    // TV devices are usually much slower at parallel SMB/directory IO than phones. Keep the
    // feature, but serialize those background count reads so D-pad navigation stays responsive.
    private val reads = Semaphore(if (BuildConfig.APPLICATION_ID.endsWith(".tv")) 1 else 4)

    val updates: SharedFlow<FolderFileCountUpdate> get() = state.updates

    fun peek(key: String): FolderDirectCounts? = state.peek(key)

    fun publish(key: String, directoryId: String, counts: FolderDirectCounts) {
        state.publish(key = key, directoryId = directoryId, counts = counts)
    }

    fun invalidate(directoryId: String) {
        state.invalidate(directoryId)
    }

    suspend fun load(
        key: String,
        entry: XEntry,
        force: Boolean = false,
    ): FolderDirectCounts? = reads.withPermit {
        if (!force) state.peek(key)?.let { return@withPermit it }
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
        state.publish(key = key, directoryId = entry.id, counts = count)
        count
    }
}

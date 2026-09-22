package app.local1st.files.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.local1st.files.BuildConfig
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.StorageSpace
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private data class FolderSizeKey(
    val id: String,
    val mtime: Long,
)

/** True for a saved SMB connection row directly below the synthetic SMB root. */
internal fun isSmbConnectionRoot(entry: XEntry): Boolean =
    entry.scheme == XId.SCHEME_SMB &&
        entry.id != "${XId.SCHEME_SMB}://" &&
        entry.path.isNotBlank() &&
        !entry.path.contains('/') &&
        !entry.path.startsWith('@')

internal fun isStorageRoot(entry: XEntry): Boolean =
    entry.kind == EntryKind.VOLUME_INTERNAL ||
        entry.kind == EntryKind.VOLUME_SD ||
        entry.kind == EntryKind.VOLUME_USB ||
        isSmbConnectionRoot(entry)

/**
 * Recursive folder size for a browser row. [startLoad] lets expanded ancestors reuse an already
 * cached value without launching another overlapping subtree scan; collapsed child folders do the
 * actual work. Recursive IO stays fully disabled unless the display setting requested it.
 */
@Composable
internal fun rememberFolderSize(
    entry: XEntry,
    enabled: Boolean,
    startLoad: Boolean,
): Long? {
    if (!enabled || entry.kind != EntryKind.DIR || isSmbConnectionRoot(entry)) return null
    FolderSizeCache.ensureInvalidationCollector()
    val key = FolderSizeKey(entry.id, entry.mtime)
    val size by produceState<Long?>(FolderSizeCache.peek(key), key, startLoad) {
        if (value == null && startLoad) value = FolderSizeCache.load(key, entry)
        Graph.opEngine.events.collect { event ->
            if (event.dirtyDirIds.any { dirty -> pathsOverlap(entry.id, dirty) }) {
                value = if (startLoad) {
                    FolderSizeCache.load(key, entry, force = true)
                } else {
                    FolderSizeCache.peek(key)
                }
            }
        }
    }
    return size
}

/** Capacity for a local volume or saved SMB share row. */
@Composable
internal fun rememberStorageSpace(entry: XEntry): StorageSpace? {
    if (!isStorageRoot(entry)) return null
    val space by produceState<StorageSpace?>(
        initialValue = StorageSpaceCache.peek(entry.id),
        key1 = entry.id,
    ) {
        if (value == null) value = StorageSpaceCache.load(entry, force = false)
        while (isActive) {
            // Capacity queries are cheap compared with recursive folder scans. Refreshing once a
            // minute also catches writes made by other devices, which never emit an XFiles op event.
            delay(STORAGE_REFRESH_MS)
            value = StorageSpaceCache.load(entry, force = true)
        }
    }
    return space
}

internal fun storageDetails(entry: XEntry, space: StorageSpace?): String {
    if (space == null) return entry.badge.orEmpty()
    val capacity = "${Format.bytes(space.freeBytes)} free of ${Format.bytes(space.totalBytes)}"
    return entry.badge?.takeIf { it.isNotBlank() }?.let { "$it · $capacity" } ?: capacity
}

private object StorageSpaceCache {
    private const val MAX_ENTRIES = 32
    private val spaces = ConcurrentHashMap<String, StorageSpace>()
    private val reads = Semaphore(if (BuildConfig.APPLICATION_ID.endsWith(".tv")) 1 else 2)

    fun peek(id: String): StorageSpace? = spaces[id]

    suspend fun load(entry: XEntry, force: Boolean): StorageSpace? = reads.withPermit {
        if (!force) spaces[entry.id]?.let { return@withPermit it }
        val loaded = withContext(Dispatchers.IO) {
            runCatching { Graph.fsRegistry.forEntry(entry).storageSpace(entry) }.getOrNull()
        } ?: return@withPermit null
        if (spaces.size >= MAX_ENTRIES) spaces.clear()
        spaces[entry.id] = loaded
        loaded
    }
}

private object FolderSizeCache {
    private const val MAX_ENTRIES = 1024
    private val sizes = ConcurrentHashMap<FolderSizeKey, Long>()
    // One recursive SMB walk at a time. Local flash can tolerate modest parallelism, while TV
    // hardware stays serialized just like the existing direct folder-count loader.
    private val localReads = Semaphore(if (BuildConfig.APPLICATION_ID.endsWith(".tv")) 1 else 2)
    private val smbReads = Semaphore(1)
    private val invalidationStarted = AtomicBoolean(false)

    fun ensureInvalidationCollector() {
        if (!invalidationStarted.compareAndSet(false, true)) return
        Graph.appScope.launch {
            Graph.opEngine.events.collect { event ->
                event.dirtyDirIds.forEach(::invalidateAround)
            }
        }
    }

    fun peek(key: FolderSizeKey): Long? = sizes[key]

    suspend fun load(
        key: FolderSizeKey,
        entry: XEntry,
        force: Boolean = false,
    ): Long? {
        if (!force) sizes[key]?.let { return it }
        val semaphore = if (entry.scheme == XId.SCHEME_SMB) smbReads else localReads
        val loaded = semaphore.withPermit {
            withContext(Dispatchers.IO) {
                runCatching { Graph.fsRegistry.forEntry(entry).directorySize(entry) }.getOrNull()
            }
        } ?: return null
        if (sizes.size >= MAX_ENTRIES) sizes.clear()
        sizes[key] = loaded
        return loaded
    }

    private fun invalidateAround(dirtyId: String) {
        sizes.keys.forEach { key ->
            if (pathsOverlap(key.id, dirtyId)) sizes.remove(key)
        }
    }
}

/** Parent/child overlap without string-prefix false positives such as /Movies and /Movies-old. */
private fun pathsOverlap(first: String, second: String): Boolean =
    isSameOrAncestor(first, second) || isSameOrAncestor(second, first)

private fun isSameOrAncestor(ancestor: String, descendant: String): Boolean {
    var current: String? = descendant
    while (current != null) {
        if (current == ancestor) return true
        current = XId.parent(current)
    }
    return false
}

private const val STORAGE_REFRESH_MS = 60_000L

package app.local1st.files.ui.browser

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import app.local1st.files.core.fs.XId
import java.io.Closeable
import java.io.File

internal data class LocalDirectoryWatchTarget(
    val id: String,
    val path: String,
)

internal data class LocalDirectoryChange(
    val directoryId: String,
    val directoryPath: String,
    val event: Int,
    val childPath: String?,
) {
    fun removedEntryId(): String? {
        if (event and (FileObserver.DELETE or FileObserver.MOVED_FROM) == 0) return null
        val child = childPath?.takeIf { it.isNotBlank() } ?: return null
        return XId.file(File(directoryPath, child).absolutePath)
    }

    fun removesWatchedDirectory(): Boolean =
        event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0
}

/** Expanded real local directories that should have an inotify-backed [FileObserver]. */
internal fun expandedLocalDirectoryTargets(state: PaneUiState): List<LocalDirectoryWatchTarget> {
    if (!state.startupSettled || state.snapshotOnly) return emptyList()
    return state.nodes.asSequence()
        .filter { node ->
            node.expanded &&
                node.entry.isContainer &&
                node.entry.scheme == XId.SCHEME_FILE &&
                node.entry.isDir
        }
        .map { node ->
            LocalDirectoryWatchTarget(
                id = node.entry.id,
                path = node.entry.localPath ?: node.entry.path,
            )
        }
        .distinctBy(LocalDirectoryWatchTarget::id)
        .toList()
}

internal fun expandedLocalDirectoryIds(state: PaneUiState): Set<String> =
    expandedLocalDirectoryTargets(state).mapTo(LinkedHashSet(), LocalDirectoryWatchTarget::id)

/**
 * Keeps exactly one observer per expanded local directory across both panes.
 *
 * FileObserver callbacks are marshalled to the main looper before touching pane state. The
 * observer instances are kept strongly referenced here because Android stops delivering events
 * once a FileObserver becomes unreachable.
 */
internal class LocalDirectoryObserverSet(
    private val onChange: (LocalDirectoryChange) -> Unit,
) : Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = LinkedHashMap<String, WatchedDirectory>()
    private var closed = false

    fun sync(states: Iterable<PaneUiState>) {
        if (closed) return
        val wanted = states.asSequence()
            .flatMap { expandedLocalDirectoryTargets(it).asSequence() }
            .associateBy(LocalDirectoryWatchTarget::id)

        val obsolete = observers.keys - wanted.keys
        obsolete.forEach { id -> observers.remove(id)?.observer?.stopWatching() }

        wanted.forEach { (id, target) ->
            val existing = observers[id]
            if (existing?.target == target) return@forEach
            existing?.observer?.stopWatching()
            val observer = createObserver(target)
            observers[id] = WatchedDirectory(target, observer)
            observer.startWatching()
        }
    }

    @Suppress("DEPRECATION")
    private fun createObserver(target: LocalDirectoryWatchTarget): FileObserver =
        object : FileObserver(target.path, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                val relevant = event and WATCH_MASK
                if (relevant == 0 || closed) return
                val change = LocalDirectoryChange(
                    directoryId = target.id,
                    directoryPath = target.path,
                    event = relevant,
                    childPath = path,
                )
                mainHandler.post {
                    if (!closed) onChange(change)
                }
            }
        }

    override fun close() {
        if (closed) return
        closed = true
        observers.values.forEach { it.observer.stopWatching() }
        observers.clear()
    }

    private data class WatchedDirectory(
        val target: LocalDirectoryWatchTarget,
        val observer: FileObserver,
    )

    private companion object {
        const val WATCH_MASK =
            FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE or
                FileObserver.ATTRIB or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF
    }
}

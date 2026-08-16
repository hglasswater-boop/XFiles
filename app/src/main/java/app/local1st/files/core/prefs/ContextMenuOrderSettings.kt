package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted order for context-menu actions. */
object ContextMenuOrderSettings {
    const val DETAILS = "details"
    const val FOLDER_SORT = "folder_sort"
    const val NEW_TEXT_FILE = "new_text_file"
    const val FAVORITE = "favorite"
    const val OPEN_WITH = "open_with"
    const val SHARE = "share"
    const val COPY_TO = "copy_to"
    const val MOVE_TO = "move_to"
    const val ZIP = "zip"
    const val EXTRACT = "extract"
    const val INSTALL = "install"
    const val RENAME = "rename"
    const val DELETE = "delete"

    val DEFAULT_ORDER = listOf(
        DETAILS, FOLDER_SORT, NEW_TEXT_FILE, FAVORITE, OPEN_WITH, SHARE,
        COPY_TO, MOVE_TO, ZIP, EXTRACT, INSTALL, RENAME, DELETE,
    )

    private const val PREFS_NAME = "context_menu_order"
    private const val KEY_ORDER = "item_order"
    private val _order = MutableStateFlow(DEFAULT_ORDER)
    private var initialized = false

    fun order(context: Context): StateFlow<List<String>> {
        ensureInitialized(context)
        return _order.asStateFlow()
    }

    fun current(context: Context): List<String> {
        ensureInitialized(context)
        return _order.value
    }

    fun set(context: Context, value: List<String>) {
        ensureInitialized(context)
        val normalized = normalize(value)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, normalized.joinToString(",")).apply()
        _order.value = normalized
    }

    fun move(context: Context, id: String, delta: Int) {
        val items = current(context).toMutableList()
        val from = items.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(items.indices)
        if (from == to) return
        items.removeAt(from)
        items.add(to, id)
        set(context, items)
    }

    fun reset(context: Context) = set(context, DEFAULT_ORDER)

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)?.split(',').orEmpty()
        _order.value = normalize(stored)
        initialized = true
    }

    private fun normalize(value: List<String>): List<String> {
        val known = DEFAULT_ORDER.toSet()
        val kept = value.filter { it in known }.distinct()
        return kept + DEFAULT_ORDER.filterNot { it in kept }
    }
}

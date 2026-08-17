package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** Recent filename searches, newest first. */
object SearchHistorySettings {
    private const val PREFS_NAME = "search_history"
    private const val KEY_HISTORY = "queries"
    private const val MAX_ENTRIES = 20

    private val _history = MutableStateFlow<List<String>>(emptyList())
    private var initialized = false

    fun history(context: Context): StateFlow<List<String>> {
        ensureInitialized(context)
        return _history.asStateFlow()
    }

    fun add(context: Context, query: String) {
        ensureInitialized(context)
        val normalized = query.trim()
        if (normalized.length < 2) return
        val next = buildList {
            add(normalized)
            addAll(_history.value.filterNot { it.equals(normalized, ignoreCase = true) })
        }.take(MAX_ENTRIES)
        persist(context, next)
    }

    fun clear(context: Context) {
        ensureInitialized(context)
        persist(context, emptyList())
    }

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null)
        _history.value = decode(raw)
        initialized = true
    }

    private fun persist(context: Context, value: List<String>) {
        val normalized = value
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
            .take(MAX_ENTRIES)
        val json = JSONArray().apply { normalized.forEach(::put) }.toString()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, json).apply()
        _history.value = normalized
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index)
                        .trim()
                        .takeIf { it.length >= 2 }
                        ?.let(::add)
                }
            }.distinctBy { it.lowercase() }.take(MAX_ENTRIES)
        }.getOrDefault(emptyList())
    }
}

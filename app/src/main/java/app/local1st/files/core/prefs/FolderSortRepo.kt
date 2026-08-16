package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A folder-specific override layered on top of the global browser sort settings. */
data class FolderSortSpec(
    val by: SortBy,
    val descending: Boolean,
    val dirsFirst: Boolean,
)

/**
 * Small synchronous preference store keyed by the semantic XEntry id.
 *
 * Keeping this separate from SettingsRepo means an upstream change to the main DataStore schema
 * is unlikely to conflict with this personal-fork feature. The map is published as StateFlow so
 * both panes re-sort immediately when an override changes.
 */
class FolderSortRepo(context: Context) {
    private val prefs = context.getSharedPreferences("folder_sort_overrides", Context.MODE_PRIVATE)
    private val _sorts = MutableStateFlow(read())
    val sorts: StateFlow<Map<String, FolderSortSpec>> = _sorts

    fun set(folderId: String, spec: FolderSortSpec?) {
        val updated = LinkedHashMap(_sorts.value)
        if (spec == null) updated.remove(folderId) else updated[folderId] = spec
        _sorts.value = updated
        prefs.edit().putString(KEY_SORTS, encode(updated)).apply()
    }

    private fun read(): Map<String, FolderSortSpec> = runCatching {
        val array = JSONArray(prefs.getString(KEY_SORTS, "[]") ?: "[]")
        buildMap {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (!id.contains("://")) continue
                val by = runCatching { SortBy.valueOf(item.optString("by")) }.getOrNull()
                    ?: continue
                put(
                    id,
                    FolderSortSpec(
                        by = by,
                        descending = item.optBoolean("descending", false),
                        dirsFirst = item.optBoolean("dirsFirst", true),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun encode(values: Map<String, FolderSortSpec>): String {
        val array = JSONArray()
        values.entries.take(MAX_FOLDER_SORTS).forEach { (id, spec) ->
            array.put(
                JSONObject()
                    .put("id", id)
                    .put("by", spec.by.name)
                    .put("descending", spec.descending)
                    .put("dirsFirst", spec.dirsFirst),
            )
        }
        return array.toString()
    }

    private companion object {
        const val KEY_SORTS = "sorts"
        const val MAX_FOLDER_SORTS = 512
    }
}

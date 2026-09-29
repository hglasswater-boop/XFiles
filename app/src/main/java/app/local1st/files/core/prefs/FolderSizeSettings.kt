package app.local1st.files.core.prefs

import android.content.Context
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Controls whether recursive folder sizes are calculated in browser rows. */
enum class FolderSizeMode {
    OFF,
    LOCAL_ONLY,
    LOCAL_AND_SMB;

    fun allows(entry: XEntry): Boolean = when (entry.scheme) {
        XId.SCHEME_FILE -> this != OFF
        XId.SCHEME_SMB -> this == LOCAL_AND_SMB
        else -> false
    }
}

/**
 * Folder sizes require recursive IO and can be expensive on large trees, especially over SMB.
 * Keep the feature explicitly opt-in rather than making ordinary browsing trigger a disk scan.
 */
object FolderSizeSettings {
    private const val PREFS = "folder_size_settings"
    private const val KEY_MODE = "mode"
    private val _mode = MutableStateFlow(FolderSizeMode.OFF)
    private var loaded = false

    fun state(context: Context): StateFlow<FolderSizeMode> {
        ensureLoaded(context)
        return _mode.asStateFlow()
    }

    fun current(context: Context): FolderSizeMode {
        ensureLoaded(context)
        return _mode.value
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        _mode.value = runCatching { FolderSizeMode.valueOf(raw ?: "") }
            .getOrDefault(FolderSizeMode.OFF)
        loaded = true
    }

    @Synchronized
    fun setMode(context: Context, mode: FolderSizeMode) {
        ensureLoaded(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        _mode.value = mode
    }
}

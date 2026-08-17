package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable behavior for the built-in video player. */
object VideoPlayerSettings {
    const val DEFAULT_SEEK_WHILE_DRAGGING = true

    private const val PREFS_NAME = "video_player"
    private const val KEY_SEEK_WHILE_DRAGGING = "seek_while_dragging"

    private val _seekWhileDragging = MutableStateFlow(DEFAULT_SEEK_WHILE_DRAGGING)
    private var initialized = false

    fun seekWhileDragging(context: Context): StateFlow<Boolean> {
        ensureInitialized(context)
        return _seekWhileDragging.asStateFlow()
    }

    fun currentSeekWhileDragging(context: Context): Boolean {
        ensureInitialized(context)
        return _seekWhileDragging.value
    }

    fun setSeekWhileDragging(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEK_WHILE_DRAGGING, enabled)
            .apply()
        _seekWhileDragging.value = enabled
    }

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        _seekWhileDragging.value = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SEEK_WHILE_DRAGGING, DEFAULT_SEEK_WHILE_DRAGGING)
        initialized = true
    }
}

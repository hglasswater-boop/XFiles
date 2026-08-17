package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted user-tunable behavior and appearance for the built-in video player. */
object VideoPlayerSettings {
    const val DEFAULT_SEEK_WHILE_DRAGGING = true
    const val DEFAULT_CONTROLS_TRANSPARENCY_PERCENT = 15

    private const val PREFS_NAME = "video_player"
    private const val KEY_SEEK_WHILE_DRAGGING = "seek_while_dragging"
    private const val KEY_CONTROLS_TRANSPARENCY_PERCENT = "controls_transparency_percent"

    private val _seekWhileDragging = MutableStateFlow(DEFAULT_SEEK_WHILE_DRAGGING)
    private val _controlsTransparencyPercent =
        MutableStateFlow(DEFAULT_CONTROLS_TRANSPARENCY_PERCENT)
    private var initialized = false

    fun seekWhileDragging(context: Context): StateFlow<Boolean> {
        ensureInitialized(context)
        return _seekWhileDragging.asStateFlow()
    }

    fun controlsTransparencyPercent(context: Context): StateFlow<Int> {
        ensureInitialized(context)
        return _controlsTransparencyPercent.asStateFlow()
    }

    fun setControlsTransparencyPercent(context: Context, percent: Int) {
        ensureInitialized(context)
        val clamped = percent.coerceIn(0, 60)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CONTROLS_TRANSPARENCY_PERCENT, clamped)
            .apply()
        _controlsTransparencyPercent.value = clamped
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
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _seekWhileDragging.value =
            prefs.getBoolean(KEY_SEEK_WHILE_DRAGGING, DEFAULT_SEEK_WHILE_DRAGGING)
        _controlsTransparencyPercent.value = prefs
            .getInt(KEY_CONTROLS_TRANSPARENCY_PERCENT, DEFAULT_CONTROLS_TRANSPARENCY_PERCENT)
            .coerceIn(0, 60)
        initialized = true
    }
}

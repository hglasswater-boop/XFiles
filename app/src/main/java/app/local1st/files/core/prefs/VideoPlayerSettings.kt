package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted user-tunable behavior and appearance for the built-in video player. */
object VideoPlayerSettings {
    enum class BufferPreset(
        val id: Int,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
    ) {
        STANDARD(
            id = 0,
            minBufferMs = 30_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
        ),
        THICK(
            id = 1,
            minBufferMs = 60_000,
            maxBufferMs = 120_000,
            bufferForPlaybackMs = 4_000,
            bufferForPlaybackAfterRebufferMs = 10_000,
        ),
        MAXIMUM(
            id = 2,
            minBufferMs = 90_000,
            maxBufferMs = 180_000,
            bufferForPlaybackMs = 5_000,
            bufferForPlaybackAfterRebufferMs = 15_000,
        );

        companion object {
            fun fromId(id: Int): BufferPreset = entries.firstOrNull { it.id == id } ?: THICK
        }
    }

    const val DEFAULT_SEEK_WHILE_DRAGGING = true
    const val DEFAULT_CONTROLS_TRANSPARENCY_PERCENT = 15
    const val DEFAULT_ORIENTATION_LOCKED = false
    val DEFAULT_BUFFER_PRESET = BufferPreset.THICK

    private const val PREFS_NAME = "video_player"
    private const val KEY_SEEK_WHILE_DRAGGING = "seek_while_dragging"
    private const val KEY_CONTROLS_TRANSPARENCY_PERCENT = "controls_transparency_percent"
    private const val KEY_ORIENTATION_LOCKED = "orientation_locked"
    private const val KEY_BUFFER_PRESET = "buffer_preset"

    private val _seekWhileDragging = MutableStateFlow(DEFAULT_SEEK_WHILE_DRAGGING)
    private val _controlsTransparencyPercent =
        MutableStateFlow(DEFAULT_CONTROLS_TRANSPARENCY_PERCENT)
    private val _orientationLocked = MutableStateFlow(DEFAULT_ORIENTATION_LOCKED)
    private val _bufferPreset = MutableStateFlow(DEFAULT_BUFFER_PRESET)
    private var initialized = false

    fun seekWhileDragging(context: Context): StateFlow<Boolean> {
        ensureInitialized(context)
        return _seekWhileDragging.asStateFlow()
    }

    fun controlsTransparencyPercent(context: Context): StateFlow<Int> {
        ensureInitialized(context)
        return _controlsTransparencyPercent.asStateFlow()
    }

    fun orientationLocked(context: Context): StateFlow<Boolean> {
        ensureInitialized(context)
        return _orientationLocked.asStateFlow()
    }

    fun bufferPreset(context: Context): StateFlow<BufferPreset> {
        ensureInitialized(context)
        return _bufferPreset.asStateFlow()
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

    fun currentOrientationLocked(context: Context): Boolean {
        ensureInitialized(context)
        return _orientationLocked.value
    }

    fun setOrientationLocked(context: Context, locked: Boolean) {
        ensureInitialized(context)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORIENTATION_LOCKED, locked)
            .apply()
        _orientationLocked.value = locked
    }

    fun currentBufferPreset(context: Context): BufferPreset {
        ensureInitialized(context)
        return _bufferPreset.value
    }

    fun setBufferPreset(context: Context, preset: BufferPreset) {
        ensureInitialized(context)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUFFER_PRESET, preset.id)
            .apply()
        _bufferPreset.value = preset
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
        _orientationLocked.value =
            prefs.getBoolean(KEY_ORIENTATION_LOCKED, DEFAULT_ORIENTATION_LOCKED)
        _bufferPreset.value = BufferPreset.fromId(
            prefs.getInt(KEY_BUFFER_PRESET, DEFAULT_BUFFER_PRESET.id),
        )
        initialized = true
    }
}

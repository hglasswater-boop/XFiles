package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable seek-thumbnail prefetch and cache behavior for the video player. */
object SeekPreviewSettings {
    const val DEFAULT_PREFETCH_MINUTES = 2
    const val DEFAULT_KEEP_ALL_BITMAPS = false
    val PREFETCH_MINUTE_OPTIONS = listOf(0, 1, 2, 3, 5)

    private const val PREFS_NAME = "seek_preview"
    private const val KEY_PREFETCH_MINUTES = "prefetch_minutes"
    private const val KEY_KEEP_ALL_BITMAPS = "keep_all_bitmaps"

    private val _prefetchMinutes = MutableStateFlow(DEFAULT_PREFETCH_MINUTES)
    private val _keepAllBitmaps = MutableStateFlow(DEFAULT_KEEP_ALL_BITMAPS)
    private var initialized = false

    fun prefetchMinutes(context: Context): StateFlow<Int> {
        ensureInitialized(context)
        return _prefetchMinutes.asStateFlow()
    }

    fun currentPrefetchMinutes(context: Context): Int {
        ensureInitialized(context)
        return _prefetchMinutes.value
    }

    fun keepAllBitmaps(context: Context): StateFlow<Boolean> {
        ensureInitialized(context)
        return _keepAllBitmaps.asStateFlow()
    }

    fun currentKeepAllBitmaps(context: Context): Boolean {
        ensureInitialized(context)
        return _keepAllBitmaps.value
    }

    fun setPrefetchMinutes(context: Context, minutes: Int) {
        ensureInitialized(context)
        val normalized = minutes.takeIf(PREFETCH_MINUTE_OPTIONS::contains)
            ?: DEFAULT_PREFETCH_MINUTES
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PREFETCH_MINUTES, normalized)
            .apply()
        _prefetchMinutes.value = normalized
    }

    fun setKeepAllBitmaps(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KEEP_ALL_BITMAPS, enabled)
            .apply()
        _keepAllBitmaps.value = enabled
    }

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedMinutes = prefs.getInt(KEY_PREFETCH_MINUTES, DEFAULT_PREFETCH_MINUTES)
        _prefetchMinutes.value = storedMinutes.takeIf(PREFETCH_MINUTE_OPTIONS::contains)
            ?: DEFAULT_PREFETCH_MINUTES
        _keepAllBitmaps.value = prefs.getBoolean(
            KEY_KEEP_ALL_BITMAPS,
            DEFAULT_KEEP_ALL_BITMAPS,
        )
        initialized = true
    }
}

package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-tunable background prefetch range for video seek thumbnails. */
object SeekPreviewSettings {
    const val DEFAULT_PREFETCH_MINUTES = 2
    val PREFETCH_MINUTE_OPTIONS = listOf(0, 1, 2, 3, 5)

    private const val PREFS_NAME = "seek_preview"
    private const val KEY_PREFETCH_MINUTES = "prefetch_minutes"

    private val _prefetchMinutes = MutableStateFlow(DEFAULT_PREFETCH_MINUTES)
    private var initialized = false

    fun prefetchMinutes(context: Context): StateFlow<Int> {
        ensureInitialized(context)
        return _prefetchMinutes.asStateFlow()
    }

    fun currentPrefetchMinutes(context: Context): Int {
        ensureInitialized(context)
        return _prefetchMinutes.value
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

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val stored = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREFETCH_MINUTES, DEFAULT_PREFETCH_MINUTES)
        _prefetchMinutes.value = stored.takeIf(PREFETCH_MINUTE_OPTIONS::contains)
            ?: DEFAULT_PREFETCH_MINUTES
        initialized = true
    }
}

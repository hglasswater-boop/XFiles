package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preference for the on-demand video storyboard shown from browser thumbnails. */
object VideoStoryboardSettings {
    const val MIN_SAMPLE_COUNT = 6
    const val MAX_SAMPLE_COUNT = 120
    const val SAMPLE_COUNT_STEP = 2
    const val DEFAULT_SAMPLE_COUNT = 10

    private const val PREFS = "video_storyboard_settings"
    private const val KEY_SAMPLE_COUNT = "sample_count"

    private val _sampleCount = MutableStateFlow(DEFAULT_SAMPLE_COUNT)
    private var loaded = false

    fun state(context: Context): StateFlow<Int> {
        ensureLoaded(context)
        return _sampleCount.asStateFlow()
    }

    fun current(context: Context): Int {
        ensureLoaded(context)
        return _sampleCount.value
    }

    fun setSampleCount(context: Context, value: Int) {
        ensureLoaded(context)
        val normalized = normalize(value)
        if (_sampleCount.value == normalized) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SAMPLE_COUNT, normalized)
            .apply()
        _sampleCount.value = normalized
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _sampleCount.value = normalize(prefs.getInt(KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT))
        loaded = true
    }

    private fun normalize(value: Int): Int {
        val migrated = when (value) {
            5 -> 6
            9 -> 10
            15 -> 16
            21 -> 20
            else -> value
        }
        val clamped = migrated.coerceIn(MIN_SAMPLE_COUNT, MAX_SAMPLE_COUNT)
        val offset = clamped - MIN_SAMPLE_COUNT
        return MIN_SAMPLE_COUNT +
            ((offset + SAMPLE_COUNT_STEP / 2) / SAMPLE_COUNT_STEP) * SAMPLE_COUNT_STEP
    }
}

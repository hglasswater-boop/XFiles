package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences for the on-demand video storyboard shown from browser thumbnails. */
object VideoStoryboardSettings {
    const val MIN_SAMPLE_COUNT = 6
    const val MAX_SAMPLE_COUNT = 120
    const val SAMPLE_COUNT_STEP = 2
    const val DEFAULT_SAMPLE_COUNT = 10

    const val MIN_SPACING_SECONDS = 1
    const val MAX_SPACING_SECONDS = 10
    const val DEFAULT_SPACING_SECONDS = 5

    private const val PREFS = "video_storyboard_settings"
    private const val KEY_SAMPLE_COUNT = "sample_count"
    private const val KEY_MIN_SPACING_SECONDS = "min_spacing_seconds"

    private val _sampleCount = MutableStateFlow(DEFAULT_SAMPLE_COUNT)
    private val _minSpacingSeconds = MutableStateFlow(DEFAULT_SPACING_SECONDS)
    private var loaded = false

    fun state(context: Context): StateFlow<Int> {
        ensureLoaded(context)
        return _sampleCount.asStateFlow()
    }

    fun spacingState(context: Context): StateFlow<Int> {
        ensureLoaded(context)
        return _minSpacingSeconds.asStateFlow()
    }

    fun current(context: Context): Int {
        ensureLoaded(context)
        return _sampleCount.value
    }

    fun currentMinSpacingSeconds(context: Context): Int {
        ensureLoaded(context)
        return _minSpacingSeconds.value
    }

    fun setSampleCount(context: Context, value: Int) {
        ensureLoaded(context)
        val normalized = normalizeSampleCount(value)
        if (_sampleCount.value == normalized) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SAMPLE_COUNT, normalized)
            .apply()
        _sampleCount.value = normalized
    }

    fun setMinSpacingSeconds(context: Context, value: Int) {
        ensureLoaded(context)
        val normalized = value.coerceIn(MIN_SPACING_SECONDS, MAX_SPACING_SECONDS)
        if (_minSpacingSeconds.value == normalized) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MIN_SPACING_SECONDS, normalized)
            .apply()
        _minSpacingSeconds.value = normalized
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _sampleCount.value = normalizeSampleCount(
            prefs.getInt(KEY_SAMPLE_COUNT, DEFAULT_SAMPLE_COUNT),
        )
        _minSpacingSeconds.value = prefs
            .getInt(KEY_MIN_SPACING_SECONDS, DEFAULT_SPACING_SECONDS)
            .coerceIn(MIN_SPACING_SECONDS, MAX_SPACING_SECONDS)
        loaded = true
    }

    private fun normalizeSampleCount(value: Int): Int {
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

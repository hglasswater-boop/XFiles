package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preference for the on-demand video storyboard shown from browser thumbnails. */
object VideoStoryboardSettings {
    val sampleCountOptions: List<Int> = listOf(5, 9, 15, 21)
    const val DEFAULT_SAMPLE_COUNT = 9

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
        val normalized = normalize(value)
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

    private fun normalize(value: Int): Int =
        value.takeIf { it in sampleCountOptions } ?: DEFAULT_SAMPLE_COUNT
}

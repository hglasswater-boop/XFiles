package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Thumbnail footprint used by the tree row. Values are dp and intentionally small enough for phones. */
enum class ThumbnailSize(val widthDp: Int, val heightDp: Int) {
    SMALL(36, 36),
    MEDIUM(56, 42),
    LARGE(80, 56),
    EXTRA_LARGE(104, 72),
}

enum class FilenameDisplayMode {
    SINGLE_LINE,
    TWO_LINES,
    FULL,
}

data class BrowserDisplayConfig(
    val thumbnailSize: ThumbnailSize = ThumbnailSize.MEDIUM,
    val filenameMode: FilenameDisplayMode = FilenameDisplayMode.TWO_LINES,
    /** Number of tree indentation levels that are visually represented. 0 = flat, max 4. */
    val treeLevels: Int = 4,
)

enum class BrowserDisplayPreset(
    val config: BrowserDisplayConfig,
) {
    COMPACT(
        BrowserDisplayConfig(
            thumbnailSize = ThumbnailSize.SMALL,
            filenameMode = FilenameDisplayMode.SINGLE_LINE,
            treeLevels = 2,
        ),
    ),
    STANDARD(
        BrowserDisplayConfig(
            thumbnailSize = ThumbnailSize.MEDIUM,
            filenameMode = FilenameDisplayMode.TWO_LINES,
            treeLevels = 4,
        ),
    ),
    MEDIA(
        BrowserDisplayConfig(
            thumbnailSize = ThumbnailSize.EXTRA_LARGE,
            filenameMode = FilenameDisplayMode.FULL,
            treeLevels = 2,
        ),
    );

    companion object {
        fun matching(config: BrowserDisplayConfig): BrowserDisplayPreset? =
            entries.firstOrNull { it.config == config }
    }
}

/**
 * Presentation-only settings are kept separate from the filesystem/session DataStore.
 * SharedPreferences is sufficient here and lets a preset update all three values atomically.
 */
object BrowserDisplaySettings {
    private const val PREFS = "browser_display_settings"
    private const val KEY_THUMBNAIL = "thumbnail_size"
    private const val KEY_FILENAME = "filename_mode"
    private const val KEY_TREE_LEVELS = "tree_levels"

    private val defaultConfig = BrowserDisplayPreset.STANDARD.config
    private val _config = MutableStateFlow(defaultConfig)
    private var loaded = false

    fun state(context: Context): StateFlow<BrowserDisplayConfig> {
        ensureLoaded(context)
        return _config.asStateFlow()
    }

    fun current(context: Context): BrowserDisplayConfig {
        ensureLoaded(context)
        return _config.value
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val thumbnail = runCatching {
            ThumbnailSize.valueOf(prefs.getString(KEY_THUMBNAIL, null) ?: "")
        }.getOrDefault(defaultConfig.thumbnailSize)
        val filename = runCatching {
            FilenameDisplayMode.valueOf(prefs.getString(KEY_FILENAME, null) ?: "")
        }.getOrDefault(defaultConfig.filenameMode)
        val treeLevels = prefs.getInt(KEY_TREE_LEVELS, defaultConfig.treeLevels).coerceIn(0, 4)
        _config.value = BrowserDisplayConfig(thumbnail, filename, treeLevels)
        loaded = true
    }

    fun applyPreset(context: Context, preset: BrowserDisplayPreset) {
        write(context, preset.config)
    }

    fun setThumbnailSize(context: Context, value: ThumbnailSize) {
        write(context, current(context).copy(thumbnailSize = value))
    }

    fun setFilenameMode(context: Context, value: FilenameDisplayMode) {
        write(context, current(context).copy(filenameMode = value))
    }

    fun setTreeLevels(context: Context, value: Int) {
        write(context, current(context).copy(treeLevels = value.coerceIn(0, 4)))
    }

    @Synchronized
    private fun write(context: Context, config: BrowserDisplayConfig) {
        ensureLoaded(context)
        val normalized = config.copy(treeLevels = config.treeLevels.coerceIn(0, 4))
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THUMBNAIL, normalized.thumbnailSize.name)
            .putString(KEY_FILENAME, normalized.filenameMode.name)
            .putInt(KEY_TREE_LEVELS, normalized.treeLevels)
            .apply()
        _config.value = normalized
    }
}

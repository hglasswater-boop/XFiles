package app.local1st.files.core.prefs

import android.content.Context

/** Device-local manual A/V sync offsets for videos opened in the built-in player. */
object VideoAvSyncStore {
    private const val PREFS_NAME = "video_av_sync_offsets"

    /**
     * Signed audio offset in milliseconds.
     *
     * Negative values advance audio (play it earlier); positive values delay audio.
     */
    fun load(context: Context, mediaId: String): Long =
        normalizeVideoAvSyncOffsetMs(prefs(context).getLong(mediaId, 0L))

    fun save(context: Context, mediaId: String, offsetMs: Long) {
        val normalized = normalizeVideoAvSyncOffsetMs(offsetMs)
        prefs(context).edit().apply {
            if (normalized == 0L) {
                remove(mediaId)
            } else {
                putLong(mediaId, normalized)
            }
        }.apply()
    }

    fun clear(context: Context, mediaId: String) {
        prefs(context).edit().remove(mediaId).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

internal fun normalizeVideoAvSyncOffsetMs(offsetMs: Long): Long =
    offsetMs.coerceIn(-VIDEO_AV_SYNC_MAX_OFFSET_MS, VIDEO_AV_SYNC_MAX_OFFSET_MS)

const val VIDEO_AV_SYNC_MAX_OFFSET_MS = 3_000L

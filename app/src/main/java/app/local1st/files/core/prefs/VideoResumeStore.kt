package app.local1st.files.core.prefs

import android.content.Context

/** Device-local playback positions for videos opened in the built-in player. */
object VideoResumeStore {
    private const val PREFS_NAME = "video_resume_positions"

    fun load(context: Context, mediaId: String): Long =
        prefs(context).getLong(mediaId, 0L).coerceAtLeast(0L)

    fun save(context: Context, mediaId: String, positionMs: Long, durationMs: Long) {
        val normalized = normalizeVideoResumePosition(positionMs, durationMs)
        prefs(context).edit().apply {
            if (normalized == null) {
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

/**
 * Ignore accidental starts and treat the tail of a completed video as watched.
 * For short videos the end guard is 5% of the duration; for long videos it is capped at 10 seconds.
 */
internal fun normalizeVideoResumePosition(positionMs: Long, durationMs: Long): Long? {
    val position = positionMs.coerceAtLeast(0L)
    if (position < VIDEO_RESUME_MIN_POSITION_MS) return null

    if (durationMs > 0L) {
        if (position >= durationMs) return null
        val endGuardMs = minOf(
            VIDEO_RESUME_MAX_END_GUARD_MS,
            (durationMs * VIDEO_RESUME_END_GUARD_PERCENT / 100L).coerceAtLeast(1L),
        )
        if (durationMs - position <= endGuardMs) return null
    }

    return position
}

private const val VIDEO_RESUME_MIN_POSITION_MS = 5_000L
private const val VIDEO_RESUME_MAX_END_GUARD_MS = 10_000L
private const val VIDEO_RESUME_END_GUARD_PERCENT = 5L

package app.local1st.files.core.prefs

import android.content.Context
import android.os.SystemClock

/** Device-local playback positions for videos opened in the built-in player. */
object VideoResumeStore {
    private const val PREFS_NAME = "video_resume_positions"
    private const val REQUESTED_START_TTL_MS = 15_000L

    private data class RequestedStart(
        val positionMs: Long,
        val requestedAtElapsedMs: Long,
    )

    private val requestedStarts = HashMap<String, RequestedStart>()

    /**
     * Queue a one-shot start position without overwriting the user's normal resume position.
     * This is used by storyboard thumbnails: the next built-in player open consumes the request.
     */
    @Synchronized
    fun requestStart(mediaId: String, positionMs: Long) {
        requestedStarts[mediaId] = RequestedStart(
            positionMs = positionMs.coerceAtLeast(0L),
            requestedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Read a still-valid explicit start without consuming it.
     * Player construction uses this so the requested storyboard timestamp is already the media
     * item's initial position before prepare/play can restore or expose any other position.
     */
    @Synchronized
    fun peekRequestedStart(mediaId: String): Long? = validRequestedStart(mediaId)?.positionMs

    @Synchronized
    fun consumeRequestedStart(mediaId: String): Long? {
        val request = validRequestedStart(mediaId) ?: return null
        requestedStarts.remove(mediaId)
        return request.positionMs
    }

    @Synchronized
    private fun validRequestedStart(mediaId: String): RequestedStart? {
        val request = requestedStarts[mediaId] ?: return null
        val ageMs = SystemClock.elapsedRealtime() - request.requestedAtElapsedMs
        if (ageMs !in 0..REQUESTED_START_TTL_MS) {
            requestedStarts.remove(mediaId)
            return null
        }
        return request
    }

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
 * Choose the position used when a fresh player session is prepared.
 * An explicit storyboard request always wins, including an intentional request for 0 ms.
 */
internal fun resolveVideoPlaybackStartPosition(requestedStartMs: Long?, resumeMs: Long): Long? =
    requestedStartMs ?: resumeMs.takeIf { it > 0L }

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

package app.local1st.files.ui.viewer

internal const val STORYBOARD_SEEK_FOLLOW_TOLERANCE_MS = 1_000L

/**
 * Returns true when a playback position update is much more likely to be an explicit seek than
 * ordinary forward playback and the coarse storyboard selection actually moved to another frame.
 *
 * Backward movement is always a seek. Forward movement is treated as a seek only when it advances
 * farther than wall-clock time plus a small tolerance, which keeps normal playback from constantly
 * pulling a storyboard that the user is browsing by hand.
 */
internal fun shouldAutoFollowStoryboard(
    previousPositionMs: Long,
    positionMs: Long,
    elapsedRealtimeMs: Long,
    previousNearestIndex: Int,
    nearestIndex: Int,
    toleranceMs: Long = STORYBOARD_SEEK_FOLLOW_TOLERANCE_MS,
): Boolean {
    if (previousNearestIndex < 0 || nearestIndex < 0) return false
    if (previousNearestIndex == nearestIndex) return false

    val deltaMs = positionMs - previousPositionMs
    if (deltaMs < 0L) return true

    val expectedForwardMs = elapsedRealtimeMs.coerceAtLeast(0L)
    return deltaMs > expectedForwardMs + toleranceMs.coerceAtLeast(0L)
}

package app.local1st.files.ui.browser

import kotlin.math.abs

internal const val STORYBOARD_DUPLICATE_SYNC_VERIFY_MIN_DISTANCE_MS = 5_000L

internal fun shouldVerifyDuplicateStoryboardSync(
    previousFingerprint: Long?,
    currentFingerprint: Long,
    previousTimeMs: Long?,
    currentTimeMs: Long,
    minDistanceMs: Long = STORYBOARD_DUPLICATE_SYNC_VERIFY_MIN_DISTANCE_MS,
): Boolean {
    if (previousFingerprint == null || previousTimeMs == null) return false
    if (previousFingerprint != currentFingerprint) return false
    return abs(currentTimeMs - previousTimeMs) >= minDistanceMs.coerceAtLeast(0L)
}

internal fun shouldPreferClosestStoryboardFrames(
    syncFingerprint: Long,
    closestFingerprint: Long?,
): Boolean = closestFingerprint != null && syncFingerprint != closestFingerprint

package app.local1st.files.ui.browser

import kotlin.math.abs

internal const val STORYBOARD_DUPLICATE_SYNC_VERIFY_MIN_DISTANCE_MS = 5_000L

/**
 * Flags a repeated sync-frame fingerprint only when the requested timestamps are meaningfully
 * separated. The caller then verifies that suspicious sample with an exact-position decode before
 * deciding that sync seeking is unreliable for the video.
 */
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

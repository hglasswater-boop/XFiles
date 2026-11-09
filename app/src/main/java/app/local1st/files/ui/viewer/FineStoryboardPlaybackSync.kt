package app.local1st.files.ui.viewer

/** The fine storyboard contains the center frame plus five frames on each side. */
internal const val FINE_STORYBOARD_SIDE_FRAME_COUNT = 5

internal fun fineStoryboardWindowEndMs(
    centerTimeMs: Long,
    stepMs: Long,
    durationMs: Long? = null,
): Long {
    val safeCenterMs = centerTimeMs.coerceAtLeast(0L)
    val safeStepMs = stepMs.coerceAtLeast(1L)
    val spanMs = safeStepMs * FINE_STORYBOARD_SIDE_FRAME_COUNT
    val rawEndMs = if (Long.MAX_VALUE - safeCenterMs < spanMs) {
        Long.MAX_VALUE
    } else {
        safeCenterMs + spanMs
    }
    val durationEndMs = durationMs
        ?.takeIf { it > 0L }
        ?.minus(1L)
        ?.coerceAtLeast(0L)
    return durationEndMs?.let(rawEndMs::coerceAtMost) ?: rawEndMs
}

/**
 * Follows forward playback only when it crosses the displayed fine window's end.
 * A manual long press on an older section must remain visible if playback was already
 * beyond that section when the user opened it. Backward movement never rewinds the view.
 */
internal fun fineStoryboardFollowTargetIndex(
    currentIndex: Int,
    previousPositionMs: Long,
    positionMs: Long,
    fineWindowEndTimesMs: List<Long>,
): Int? {
    if (currentIndex !in fineWindowEndTimesMs.indices) return null
    if (currentIndex == fineWindowEndTimesMs.lastIndex) return null
    val windowEndMs = fineWindowEndTimesMs[currentIndex]
    if (previousPositionMs > windowEndMs || positionMs <= windowEndMs) return null

    return ((currentIndex + 1)..fineWindowEndTimesMs.lastIndex)
        .firstOrNull { index -> positionMs <= fineWindowEndTimesMs[index] }
        ?: fineWindowEndTimesMs.lastIndex
}

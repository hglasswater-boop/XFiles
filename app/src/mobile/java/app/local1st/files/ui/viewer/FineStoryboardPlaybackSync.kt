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
 * Returns the later fine-storyboard section that should take over after playback passes the
 * currently displayed fine window. Backward movement never pulls the user's detail view back.
 */
internal fun fineStoryboardFollowTargetIndex(
    currentIndex: Int,
    positionMs: Long,
    fineWindowEndTimesMs: List<Long>,
): Int? {
    if (currentIndex !in fineWindowEndTimesMs.indices) return null
    if (currentIndex == fineWindowEndTimesMs.lastIndex) return null
    if (positionMs <= fineWindowEndTimesMs[currentIndex]) return null

    return ((currentIndex + 1)..fineWindowEndTimesMs.lastIndex)
        .firstOrNull { index -> positionMs <= fineWindowEndTimesMs[index] }
        ?: fineWindowEndTimesMs.lastIndex
}

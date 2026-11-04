package app.local1st.files.ui.viewer

import androidx.media3.common.Player

/** Folder order, not Cast's single-item receiver queue, determines the automatic direction. */
internal enum class CastAutoAdvanceMode {
    OFF,
    NEXT,
    PREVIOUS,
}

/** Processes a natural remote ending once per playback of the same video. */
internal class CastAutoAdvance {
    private var handledMediaId: String? = null

    fun nextOnEnd(
        mode: CastAutoAdvanceMode,
        entryIds: List<String>,
        selectedMediaId: String?,
        playbackState: Int,
        handoffPending: Boolean,
        playbackFailed: Boolean,
        isRemote: Boolean,
    ): Int? {
        if (!isRemote || playbackFailed) {
            handledMediaId = null
            return null
        }
        if (handoffPending || selectedMediaId == null) return null
        if (playbackState == Player.STATE_READY && handledMediaId == selectedMediaId) {
            handledMediaId = null // Explicit replay of the same video starts a fresh completion.
        }
        if (playbackState != Player.STATE_ENDED || handledMediaId == selectedMediaId) return null

        handledMediaId = selectedMediaId // Consume even in Off mode or at either boundary.
        val currentIndex = entryIds.indexOf(selectedMediaId).takeIf { it >= 0 } ?: return null
        val targetIndex = when (mode) {
            CastAutoAdvanceMode.OFF -> return null
            CastAutoAdvanceMode.NEXT -> currentIndex + 1
            CastAutoAdvanceMode.PREVIOUS -> currentIndex - 1
        }
        return targetIndex.takeIf { it in entryIds.indices }
    }
}

package app.local1st.files.ui.viewer

/**
 * Keeps the media selected by the controller authoritative while a Cast receiver is switching
 * items. Cast can briefly report the previously playing item after a remote handoff or seek. Those
 * stale observations must not flow back into controller UI.
 *
 * A handoff completes only when the remote player itself reports the target media id. Disconnects
 * and playback failures abort the handoff so we never pin the UI to a target that cannot start.
 */
internal class CastHandoffTracker(initialLocalMediaId: String?) {
    private var lastLocalMediaId: String? = initialLocalMediaId
    private var targetMediaId: String? = null

    val pendingTargetMediaId: String?
        get() = targetMediaId

    val isPending: Boolean
        get() = targetMediaId != null

    fun noteLocalMedia(mediaId: String?) {
        targetMediaId = null
        if (mediaId != null) lastLocalMediaId = mediaId
    }

    /** Starts or supersedes a remote handoff. A newer selection always wins. */
    fun beginRemoteHandoff(explicitTargetMediaId: String? = null): String? {
        val target = explicitTargetMediaId ?: lastLocalMediaId
        targetMediaId = target
        return target
    }

    /**
     * Records the latest underlying player observation and returns the media id that UI should use.
     */
    fun observe(
        isRemote: Boolean,
        reportedMediaId: String?,
        playbackFailed: Boolean = false,
    ): String? {
        if (!isRemote || playbackFailed) {
            targetMediaId = null
            if (!isRemote && reportedMediaId != null) lastLocalMediaId = reportedMediaId
            return reportedMediaId
        }

        val target = targetMediaId ?: return reportedMediaId
        if (reportedMediaId == target) {
            targetMediaId = null
            return reportedMediaId
        }
        return target
    }

    fun presentationMediaId(isRemote: Boolean, reportedMediaId: String?): String? =
        if (isRemote) targetMediaId ?: reportedMediaId else reportedMediaId

    fun abort(reportedLocalMediaId: String? = null) {
        targetMediaId = null
        if (reportedLocalMediaId != null) lastLocalMediaId = reportedLocalMediaId
    }
}

internal sealed interface ReusedRemoteSelectionAction {
    data object None : ReusedRemoteSelectionAction

    data class SeekToDefault(
        val mediaItemIndex: Int,
    ) : ReusedRemoteSelectionAction

    data class SeekToPosition(
        val mediaItemIndex: Int,
        val positionMs: Long,
    ) : ReusedRemoteSelectionAction
}

/**
 * Decides which command is required when an already-remote session is reused.
 *
 * Selecting a different item always emits an explicit seek/load command, even when there is no
 * stored resume position. Selecting the already-current item is left alone unless the caller has
 * an explicit one-shot start request (for example from a storyboard thumbnail).
 */
internal fun reusedRemoteSelectionAction(
    currentMediaId: String?,
    targetMediaId: String,
    targetIndex: Int,
    requestedStartMs: Long?,
    resolvedStartMs: Long?,
): ReusedRemoteSelectionAction = when {
    currentMediaId != targetMediaId && resolvedStartMs != null ->
        ReusedRemoteSelectionAction.SeekToPosition(targetIndex, resolvedStartMs)

    currentMediaId != targetMediaId ->
        ReusedRemoteSelectionAction.SeekToDefault(targetIndex)

    requestedStartMs != null ->
        ReusedRemoteSelectionAction.SeekToPosition(targetIndex, requestedStartMs)

    else -> ReusedRemoteSelectionAction.None
}

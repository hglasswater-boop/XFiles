package app.local1st.files.ui.viewer

import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlayerTransferState
import androidx.media3.common.util.UnstableApi

/**
 * Keeps the Cast receiver queue intentionally single-item while preserving the local folder
 * playlist. Sending an entire folder to Cast makes the receiver churn through queue metadata and
 * also couples remote playback to the folder that happened to create the player.
 */
@UnstableApi
internal class XFilesCastTransferCallback(
    private val remotePlayer: RemoteCastPlayer,
) : CastPlayer.TransferCallback {
    override fun transferState(sourcePlayer: Player, targetPlayer: Player) {
        val sourceState = PlayerTransferState.fromPlayer(sourcePlayer)

        if (targetPlayer === remotePlayer) {
            val currentItem = sourcePlayer.currentMediaItem
            val remoteState = if (currentItem == null) {
                sourceState.buildUpon()
                    .setMediaItems(emptyList())
                    .setCurrentMediaItemIndex(C.INDEX_UNSET)
                    .setCurrentPosition(0L)
                    .build()
            } else {
                sourceState.buildUpon()
                    .setMediaItems(listOf(currentItem))
                    .setCurrentMediaItemIndex(0)
                    .build()
            }
            remoteState.setToPlayer(targetPlayer)
            return
        }

        // The inactive local ExoPlayer deliberately keeps the full folder playlist while Cast is
        // active. On return, merge the remote position/play state into that existing playlist
        // instead of replacing it with the receiver's one-item queue.
        val localItems = List(targetPlayer.mediaItemCount, targetPlayer::getMediaItemAt)
        val remoteMediaId = sourcePlayer.currentMediaItem?.mediaId
        val localIndex = remoteMediaId?.let { mediaId ->
            localItems.indexOfFirst { it.mediaId == mediaId }.takeIf { it >= 0 }
        }

        if (localItems.isNotEmpty() && localIndex != null) {
            sourceState.buildUpon()
                .setMediaItems(localItems)
                .setCurrentMediaItemIndex(localIndex)
                .build()
                .setToPlayer(targetPlayer)
        } else {
            // Defensive fallback for an externally changed Cast queue. Correct media identity is
            // more important than preserving a stale local folder playlist in this rare case.
            sourceState.setToPlayer(targetPlayer)
        }
    }
}

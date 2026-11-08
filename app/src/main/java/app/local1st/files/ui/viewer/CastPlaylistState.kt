package app.local1st.files.ui.viewer

import androidx.media3.common.MediaItem
import app.local1st.files.core.fs.XEntry

/**
 * The active Cast player and HTTP relay outlive individual folder viewers. Rebind the playlist
 * in place when a new folder is selected, so receiver state and in-flight relay streams survive.
 */
internal class CastPlaylistState(
    entries: List<XEntry>,
    mediaItems: List<MediaItem>,
) {
    private data class Snapshot(
        val entries: List<XEntry>,
        val mediaItems: List<MediaItem>,
    ) {
        val entryIds = entries.map(XEntry::id)

        init {
            require(entryIds == mediaItems.map(MediaItem::mediaId)) {
                "Cast entries and media items must have matching IDs and order"
            }
        }
    }

    @Volatile
    private var snapshot = Snapshot(entries.toList(), mediaItems.toList())

    val entries: List<XEntry> get() = snapshot.entries
    val mediaItems: List<MediaItem> get() = snapshot.mediaItems
    val entryIds: List<String> get() = snapshot.entryIds

    fun replace(entries: List<XEntry>, mediaItems: List<MediaItem>) {
        snapshot = Snapshot(entries.toList(), mediaItems.toList())
    }
}

/** A prior viewer must never detach the new viewer's Cast ownership. */
internal fun isCurrentCastViewerOwner(currentOwner: Any?, releasedOwner: Any): Boolean =
    currentOwner === releasedOwner

/** Prevent a disposed viewer from writing the new folder's playback position to an old file. */
internal fun isMatchingPlaybackMediaId(entryId: String, currentMediaId: String?): Boolean =
    entryId == currentMediaId

package app.local1st.files.ui.viewer

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.local1st.files.core.cast.CastPlaybackBridge
import app.local1st.files.core.cast.CastPlaybackKeepAliveService
import app.local1st.files.core.cast.CastPlaybackNotificationController
import app.local1st.files.core.cast.CastPlaybackNotificationState
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.prefs.VideoResumeStore
import app.local1st.files.core.prefs.resolveVideoPlaybackStartPosition
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Process-scoped owner for mobile Cast playback.
 *
 * A Cast receiver may still be reading media through [CastMediaRelay] after the viewer screen is
 * dismissed. Keeping the player stack and relay here lets that playback continue while the user
 * returns to the file browser. While playback is remote, a media-playback foreground service also
 * keeps this process and relay eligible to run when XFiles itself is backgrounded.
 */
@UnstableApi
internal object CastPlaybackSessionManager {
    data class ActiveCastPlayback(
        val entry: XEntry,
        val playlist: List<XEntry>,
        val playing: Boolean,
        val hasPrevious: Boolean,
        val hasNext: Boolean,
    )

    internal class Session internal constructor(
        val entryIds: List<String>,
        val entries: List<XEntry>,
        val relay: CastMediaRelay,
        val localPlayer: ExoPlayer,
        val remotePlayer: RemoteCastPlayer,
        val castPlayer: CastPlayer,
        val player: Player,
        val handoffTracker: CastHandoffTracker,
        val notificationController: CastPlaybackNotificationController,
        var lifecycleListener: Player.Listener? = null,
    )

    /**
     * Presents the full folder playlist to XFiles while keeping the actual Cast receiver queue at
     * exactly one item. Local playback still uses the real ExoPlayer playlist. Remote index jumps
     * are translated into replacing the receiver's sole item.
     */
    private class HandoffAwarePlayer(
        private val delegatePlayer: Player,
        private val entryIds: List<String>,
        private val mediaItems: List<MediaItem>,
        private val handoffTracker: CastHandoffTracker,
        private val onRemoteSelection: (mediaItemIndex: Int, positionMs: Long?) -> Unit,
    ) : ForwardingPlayer(delegatePlayer) {
        private fun isRemote(): Boolean =
            delegatePlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

        private fun presentedIndex(): Int? {
            if (!isRemote()) return null
            val mediaId = handoffTracker.pendingTargetMediaId
                ?: delegatePlayer.currentMediaItem?.mediaId
                ?: return null
            return entryIds.indexOf(mediaId).takeIf { it >= 0 }
        }

        override fun getCurrentMediaItemIndex(): Int =
            presentedIndex() ?: super.getCurrentMediaItemIndex()

        override fun getMediaItemCount(): Int =
            if (isRemote()) mediaItems.size else super.getMediaItemCount()

        override fun getMediaItemAt(index: Int): MediaItem =
            if (isRemote()) mediaItems[index] else super.getMediaItemAt(index)

        override fun getCurrentMediaItem(): MediaItem? =
            presentedIndex()?.let(mediaItems::getOrNull) ?: super.getCurrentMediaItem()

        override fun getMediaMetadata(): MediaMetadata =
            presentedIndex()
                ?.let(mediaItems::getOrNull)
                ?.mediaMetadata
                ?: super.getMediaMetadata()

        override fun hasPreviousMediaItem(): Boolean =
            if (isRemote()) (presentedIndex() ?: 0) > 0 else super.hasPreviousMediaItem()

        override fun hasNextMediaItem(): Boolean =
            if (isRemote()) {
                val index = presentedIndex() ?: 0
                index < mediaItems.lastIndex
            } else {
                super.hasNextMediaItem()
            }

        override fun getPreviousMediaItemIndex(): Int =
            if (isRemote()) {
                val index = presentedIndex() ?: return C.INDEX_UNSET
                (index - 1).takeIf { it >= 0 } ?: C.INDEX_UNSET
            } else {
                super.getPreviousMediaItemIndex()
            }

        override fun getNextMediaItemIndex(): Int =
            if (isRemote()) {
                val index = presentedIndex() ?: return C.INDEX_UNSET
                (index + 1).takeIf { it <= mediaItems.lastIndex } ?: C.INDEX_UNSET
            } else {
                super.getNextMediaItemIndex()
            }

        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (isRemote()) {
                if (mediaItemIndex in mediaItems.indices) onRemoteSelection(mediaItemIndex, null)
            } else {
                super.seekToDefaultPosition(mediaItemIndex)
            }
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (isRemote()) {
                if (mediaItemIndex in mediaItems.indices) onRemoteSelection(mediaItemIndex, positionMs)
            } else {
                super.seekTo(mediaItemIndex, positionMs)
            }
        }

        override fun seekToPreviousMediaItem() {
            if (isRemote()) {
                getPreviousMediaItemIndex()
                    .takeIf { it != C.INDEX_UNSET }
                    ?.let { onRemoteSelection(it, null) }
            } else {
                super.seekToPreviousMediaItem()
            }
        }

        override fun seekToNextMediaItem() {
            if (isRemote()) {
                getNextMediaItemIndex()
                    .takeIf { it != C.INDEX_UNSET }
                    ?.let { onRemoteSelection(it, null) }
            } else {
                super.seekToNextMediaItem()
            }
        }
    }

    private val lock = Any()
    private var activeSession: Session? = null
    private var viewerSession: Session? = null
    private var serviceContext: Context? = null
    private var keepAliveRunning = false

    private val _activePlayback = MutableStateFlow<ActiveCastPlayback?>(null)
    val activePlayback = _activePlayback.asStateFlow()

    private val openControlRequestChannel = Channel<Unit>(Channel.CONFLATED)
    val openControlRequests = openControlRequestChannel.receiveAsFlow()

    fun requestOpenControls() {
        openControlRequestChannel.trySend(Unit)
    }

    fun acquire(
        context: Context,
        entries: List<XEntry>,
        mediaItems: List<MediaItem>,
        startIndex: Int,
    ): Session = synchronized(lock) {
        serviceContext = context.applicationContext
        val ids = entries.map { it.id }
        val resolvedStartIndex = startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
        val startEntry = entries.getOrNull(resolvedStartIndex)
        val requestedStartMs = startEntry?.let { VideoResumeStore.peekRequestedStart(it.id) }
        val resumeStartMs = startEntry?.let { VideoResumeStore.load(context, it.id) } ?: 0L
        val initialStartMs = resolveVideoPlaybackStartPosition(requestedStartMs, resumeStartMs)
        val existing = activeSession
        if (existing != null && existing.entryIds == ids && isRemote(existing)) {
            val targetMediaId = startEntry?.id
            if (targetMediaId != null) {
                val reportedRemoteMediaId = existing.remotePlayer.currentMediaItem?.mediaId
                existing.handoffTracker.beginRemoteHandoff(targetMediaId)
                existing.handoffTracker.observe(
                    isRemote = true,
                    reportedMediaId = reportedRemoteMediaId,
                )
                prewarmCastWindow(existing.relay, existing.entries, resolvedStartIndex)

                when (
                    val action = reusedRemoteSelectionAction(
                        currentMediaId = reportedRemoteMediaId,
                        targetMediaId = targetMediaId,
                        targetIndex = resolvedStartIndex,
                        requestedStartMs = requestedStartMs,
                        resolvedStartMs = initialStartMs,
                    )
                ) {
                    ReusedRemoteSelectionAction.None -> Unit
                    is ReusedRemoteSelectionAction.SeekToDefault ->
                        existing.player.seekToDefaultPosition(action.mediaItemIndex)
                    is ReusedRemoteSelectionAction.SeekToPosition ->
                        existing.player.seekTo(action.mediaItemIndex, action.positionMs)
                }
            }
            viewerSession = existing
            publishRemoteStateLocked(existing)
            updateKeepAliveLocked()
            return@synchronized existing
        }

        val appContext = context.applicationContext
        val relay = CastMediaRelay(appContext, entries)
        prewarmCastWindow(relay, entries, resolvedStartIndex)
        val localPlayer = ExoPlayer.Builder(
            appContext,
            TimestampRateCorrectingRenderersFactory(appContext),
        )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext).setDataSourceFactory(
                    DefaultDataSource.Factory(appContext, XFilesRemoteDataSource.Factory()),
                ),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        val remotePlayer = RemoteCastPlayer.Builder(appContext)
            .setMediaItemConverter(
                XFilesCastMediaItemConverter(
                    relay = relay,
                    originals = mediaItems.associateBy { it.mediaId },
                ),
            )
            .build()
        val castPlayer = CastPlayer.Builder(appContext)
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remotePlayer)
            .setTransferCallback(XFilesCastTransferCallback(remotePlayer))
            .build()
        val handoffTracker = CastHandoffTracker(startEntry?.id)
        val presentationPlayer = HandoffAwarePlayer(
            delegatePlayer = castPlayer,
            entryIds = ids,
            mediaItems = mediaItems,
            handoffTracker = handoffTracker,
            onRemoteSelection = { mediaItemIndex, positionMs ->
                selectRemoteMedia(
                    castPlayer = castPlayer,
                    handoffTracker = handoffTracker,
                    relay = relay,
                    entries = entries,
                    mediaItems = mediaItems,
                    mediaItemIndex = mediaItemIndex,
                    positionMs = positionMs,
                )
            },
        )
        val notificationController = object : CastPlaybackNotificationController {
            override fun togglePlayPause() {
                if (castPlayer.isPlaying) castPlayer.pause() else castPlayer.play()
            }

            override fun seekBy(deltaMs: Long) {
                val duration = castPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                val target = (castPlayer.currentPosition + deltaMs).coerceAtLeast(0L)
                castPlayer.seekTo(duration?.let { target.coerceAtMost(it) } ?: target)
            }

            override fun previous() {
                if (presentationPlayer.hasPreviousMediaItem()) {
                    presentationPlayer.seekToPreviousMediaItem()
                }
            }

            override fun next() {
                if (presentationPlayer.hasNextMediaItem()) {
                    presentationPlayer.seekToNextMediaItem()
                }
            }
        }

        var lastPrewarmedIndex = resolvedStartIndex
        var remotePreviously = castPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        if (remotePreviously) {
            handoffTracker.beginRemoteHandoff(startEntry?.id)
        }

        lateinit var created: Session
        val lifecycleListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                synchronized(lock) {
                    if (activeSession !== created) return
                    val remoteNow = isRemote(created)
                    if (remoteNow && !remotePreviously) {
                        created.handoffTracker.beginRemoteHandoff()
                    }
                    if (remoteNow) {
                        created.handoffTracker.observe(
                            isRemote = true,
                            reportedMediaId = created.remotePlayer.currentMediaItem?.mediaId,
                            playbackFailed = player.playerError != null ||
                                created.remotePlayer.playerError != null,
                        )
                    } else {
                        created.handoffTracker.noteLocalMedia(player.currentMediaItem?.mediaId)
                    }
                    remotePreviously = remoteNow

                    val currentIndex = created.player.currentMediaItemIndex
                        .coerceIn(0, created.entries.lastIndex)
                    if (currentIndex != lastPrewarmedIndex) {
                        lastPrewarmedIndex = currentIndex
                        prewarmCastWindow(created.relay, created.entries, currentIndex)
                    }
                    publishRemoteStateLocked(created)
                }
            }

            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                synchronized(lock) {
                    if (activeSession !== created) return

                    val remoteNow = deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
                    if (remoteNow && !remotePreviously) {
                        created.handoffTracker.beginRemoteHandoff()
                        created.handoffTracker.observe(
                            isRemote = true,
                            reportedMediaId = created.remotePlayer.currentMediaItem?.mediaId,
                        )
                    } else if (!remoteNow) {
                        created.handoffTracker.noteLocalMedia(created.castPlayer.currentMediaItem?.mediaId)
                    }
                    remotePreviously = remoteNow

                    if (remoteNow) {
                        publishRemoteStateLocked(created)
                        updateKeepAliveLocked()
                    } else if (viewerSession !== created) {
                        destroyLocked(created)
                    } else {
                        publishRemoteStateLocked(created)
                        updateKeepAliveLocked()
                    }
                }
            }
        }
        created = Session(
            entryIds = ids,
            entries = entries,
            relay = relay,
            localPlayer = localPlayer,
            remotePlayer = remotePlayer,
            castPlayer = castPlayer,
            player = presentationPlayer,
            handoffTracker = handoffTracker,
            notificationController = notificationController,
            lifecycleListener = lifecycleListener,
        )
        castPlayer.addListener(lifecycleListener)

        if (isRemote(created)) {
            // Cast was already connected before this viewer/session was created. Keep the local
            // folder playlist ready for a future transfer back, but load only the selected item on
            // the receiver. This is the critical cross-folder path.
            localPlayer.setMediaItems(
                mediaItems,
                resolvedStartIndex,
                initialStartMs ?: C.TIME_UNSET,
            )
            selectRemoteMedia(
                castPlayer = castPlayer,
                handoffTracker = handoffTracker,
                relay = relay,
                entries = entries,
                mediaItems = mediaItems,
                mediaItemIndex = resolvedStartIndex,
                positionMs = initialStartMs,
            )
        } else {
            castPlayer.setMediaItems(
                mediaItems,
                resolvedStartIndex,
                initialStartMs ?: C.TIME_UNSET,
            )
            castPlayer.prepare()
            castPlayer.playWhenReady = true
        }

        activeSession = created
        viewerSession = created
        if (isRemote(created)) {
            handoffTracker.observe(
                isRemote = true,
                reportedMediaId = remotePlayer.currentMediaItem?.mediaId,
            )
        } else {
            handoffTracker.noteLocalMedia(castPlayer.currentMediaItem?.mediaId ?: startEntry?.id)
        }
        publishRemoteStateLocked(created)
        updateKeepAliveLocked()
        if (existing != null) destroyDetachedLocked(existing)
        created
    }

    fun releaseViewer(session: Session) {
        synchronized(lock) {
            if (viewerSession === session) viewerSession = null
            if (activeSession === session && !isRemote(session)) {
                destroyLocked(session)
            } else {
                publishRemoteStateLocked(session)
                updateKeepAliveLocked()
            }
        }
    }

    private fun selectRemoteMedia(
        castPlayer: CastPlayer,
        handoffTracker: CastHandoffTracker,
        relay: CastMediaRelay,
        entries: List<XEntry>,
        mediaItems: List<MediaItem>,
        mediaItemIndex: Int,
        positionMs: Long?,
    ) {
        val targetItem = mediaItems.getOrNull(mediaItemIndex) ?: return
        handoffTracker.beginRemoteHandoff(targetItem.mediaId)
        entries.getOrNull(mediaItemIndex)?.id?.let { relay.prewarm(listOf(it)) }

        if (positionMs != null) {
            castPlayer.setMediaItem(targetItem, positionMs)
        } else {
            castPlayer.setMediaItem(targetItem)
        }
        castPlayer.prepare()
        castPlayer.playWhenReady = true
    }

    private fun prewarmCastWindow(relay: CastMediaRelay, entries: List<XEntry>, centerIndex: Int) {
        if (entries.isEmpty()) return
        val center = centerIndex.coerceIn(0, entries.lastIndex)
        relay.prewarm(
            listOf(center, center + 1, center - 1)
                .filter { it in entries.indices }
                .distinct()
                .map { entries[it].id },
        )
    }

    private fun isRemote(session: Session): Boolean =
        session.castPlayer.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    private fun publishRemoteStateLocked(session: Session) {
        if (activeSession !== session || !isRemote(session)) {
            if (activeSession === session) _activePlayback.value = null
            CastPlaybackBridge.detach(session.notificationController)
            return
        }

        val index = session.player.currentMediaItemIndex.coerceIn(0, session.entries.lastIndex)
        val handoffPending = session.handoffTracker.isPending
        val playback = ActiveCastPlayback(
            entry = session.entries[index],
            playlist = session.entries,
            playing = !handoffPending && session.castPlayer.isPlaying,
            hasPrevious = session.player.hasPreviousMediaItem(),
            hasNext = session.player.hasNextMediaItem(),
        )
        _activePlayback.value = playback

        val notificationState = CastPlaybackNotificationState(
            title = playback.entry.name,
            playing = playback.playing,
            hasPrevious = playback.hasPrevious,
            hasNext = playback.hasNext,
        )
        CastPlaybackBridge.attach(session.notificationController, notificationState)
        if (keepAliveRunning) {
            serviceContext?.let(CastPlaybackKeepAliveService::refresh)
        }
    }

    private fun updateKeepAliveLocked() {
        val context = serviceContext ?: return
        val shouldRun = activeSession?.let(::isRemote) == true
        if (shouldRun == keepAliveRunning) return

        keepAliveRunning = shouldRun
        if (shouldRun) {
            CastPlaybackKeepAliveService.start(context)
        } else {
            CastPlaybackKeepAliveService.stop(context)
        }
    }

    private fun destroyLocked(session: Session) {
        if (activeSession === session) {
            activeSession = null
            _activePlayback.value = null
        }
        if (viewerSession === session) viewerSession = null
        session.handoffTracker.abort()
        CastPlaybackBridge.detach(session.notificationController)
        updateKeepAliveLocked()
        destroyDetachedLocked(session)
    }

    private fun destroyDetachedLocked(session: Session) {
        session.handoffTracker.abort()
        CastPlaybackBridge.detach(session.notificationController)
        session.lifecycleListener?.let { listener ->
            runCatching { session.castPlayer.removeListener(listener) }
        }
        session.lifecycleListener = null
        runCatching { session.localPlayer.pause() }
        runCatching { session.castPlayer.release() }
        runCatching { session.remotePlayer.release() }
        runCatching { session.localPlayer.release() }
        runCatching { session.relay.close() }
    }
}

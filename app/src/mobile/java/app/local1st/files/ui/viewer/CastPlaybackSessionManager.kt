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

    private class HandoffAwarePlayer(
        private val delegatePlayer: Player,
        private val entryIds: List<String>,
        private val mediaItems: List<MediaItem>,
        private val handoffTracker: CastHandoffTracker,
    ) : ForwardingPlayer(delegatePlayer) {
        private fun pendingTargetIndex(): Int? {
            if (delegatePlayer.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) return null
            val targetId = handoffTracker.pendingTargetMediaId ?: return null
            return entryIds.indexOf(targetId).takeIf { it >= 0 }
        }

        override fun getCurrentMediaItemIndex(): Int =
            pendingTargetIndex() ?: super.getCurrentMediaItemIndex()

        override fun getCurrentMediaItem(): MediaItem? =
            pendingTargetIndex()?.let(mediaItems::getOrNull) ?: super.getCurrentMediaItem()

        override fun getMediaMetadata(): MediaMetadata =
            pendingTargetIndex()
                ?.let(mediaItems::getOrNull)
                ?.mediaMetadata
                ?: super.getMediaMetadata()
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
                    is ReusedRemoteSelectionAction.SeekToDefault -> {
                        existing.castPlayer.seekToDefaultPosition(action.mediaItemIndex)
                        existing.castPlayer.playWhenReady = true
                    }
                    is ReusedRemoteSelectionAction.SeekToPosition -> {
                        existing.castPlayer.seekTo(action.mediaItemIndex, action.positionMs)
                        existing.castPlayer.playWhenReady = true
                    }
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
        val localPlayer = ExoPlayer.Builder(appContext)
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
            .build()
        val handoffTracker = CastHandoffTracker(startEntry?.id)
        val presentationPlayer = HandoffAwarePlayer(
            delegatePlayer = castPlayer,
            entryIds = ids,
            mediaItems = mediaItems,
            handoffTracker = handoffTracker,
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
                if (!castPlayer.hasPreviousMediaItem()) return
                val targetIndex = (castPlayer.currentMediaItemIndex - 1).coerceAtLeast(0)
                relay.prewarm(listOfNotNull(entries.getOrNull(targetIndex)?.id))
                castPlayer.seekToDefaultPosition(targetIndex)
            }

            override fun next() {
                if (!castPlayer.hasNextMediaItem()) return
                val targetIndex = (castPlayer.currentMediaItemIndex + 1)
                    .coerceAtMost(entries.lastIndex)
                relay.prewarm(listOfNotNull(entries.getOrNull(targetIndex)?.id))
                castPlayer.seekToDefaultPosition(targetIndex)
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
        castPlayer.setMediaItems(
            mediaItems,
            resolvedStartIndex,
            initialStartMs ?: C.TIME_UNSET,
        )
        castPlayer.prepare()
        castPlayer.playWhenReady = true

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
            hasPrevious = if (handoffPending) index > 0 else session.castPlayer.hasPreviousMediaItem(),
            hasNext = if (handoffPending) index < session.entries.lastIndex else session.castPlayer.hasNextMediaItem(),
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

package app.local1st.files.ui.viewer

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.local1st.files.core.fs.XEntry

/**
 * Process-scoped owner for mobile Cast playback.
 *
 * A Cast receiver may still be reading media through [CastMediaRelay] after the viewer screen is
 * dismissed. Keeping the player stack and relay here lets that playback continue while the user
 * returns to the file browser. Local-only playback is still released as soon as the viewer closes.
 */
@UnstableApi
internal object CastPlaybackSessionManager {
    internal class Session internal constructor(
        val entryIds: List<String>,
        val relay: CastMediaRelay,
        val localPlayer: ExoPlayer,
        val remotePlayer: RemoteCastPlayer,
        val player: CastPlayer,
        var lifecycleListener: Player.Listener? = null,
    )

    private val lock = Any()
    private var activeSession: Session? = null
    private var viewerSession: Session? = null

    fun acquire(
        context: Context,
        entries: List<XEntry>,
        mediaItems: List<MediaItem>,
        startIndex: Int,
    ): Session = synchronized(lock) {
        val ids = entries.map { it.id }
        val existing = activeSession
        if (existing != null && existing.entryIds == ids && isRemote(existing)) {
            viewerSession = existing
            return@synchronized existing
        }

        val appContext = context.applicationContext
        val relay = CastMediaRelay(appContext, entries)
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

        lateinit var created: Session
        val lifecycleListener = object : Player.Listener {
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                synchronized(lock) {
                    if (activeSession !== created || viewerSession === created) return
                    if (deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                        destroyLocked(created)
                    }
                }
            }
        }
        created = Session(
            entryIds = ids,
            relay = relay,
            localPlayer = localPlayer,
            remotePlayer = remotePlayer,
            player = castPlayer,
            lifecycleListener = lifecycleListener,
        )
        castPlayer.addListener(lifecycleListener)
        castPlayer.setMediaItems(
            mediaItems,
            startIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0)),
            C.TIME_UNSET,
        )
        castPlayer.prepare()
        castPlayer.playWhenReady = true

        activeSession = created
        viewerSession = created
        if (existing != null) destroyDetachedLocked(existing)
        created
    }

    fun releaseViewer(session: Session) {
        synchronized(lock) {
            if (viewerSession === session) viewerSession = null
            if (activeSession === session && !isRemote(session)) {
                destroyLocked(session)
            }
        }
    }

    private fun isRemote(session: Session): Boolean =
        session.player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    private fun destroyLocked(session: Session) {
        if (activeSession === session) activeSession = null
        if (viewerSession === session) viewerSession = null
        destroyDetachedLocked(session)
    }

    private fun destroyDetachedLocked(session: Session) {
        session.lifecycleListener?.let { listener ->
            runCatching { session.player.removeListener(listener) }
        }
        session.lifecycleListener = null
        runCatching { session.localPlayer.pause() }
        runCatching { session.player.release() }
        runCatching { session.remotePlayer.release() }
        runCatching { session.localPlayer.release() }
        runCatching { session.relay.close() }
    }
}

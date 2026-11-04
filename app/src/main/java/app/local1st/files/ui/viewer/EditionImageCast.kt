package app.local1st.files.ui.viewer

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.util.UnstableApi
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import java.io.Closeable

/**
 * The viewer owns the LAN relay while open. Cast's session remains owned by the SDK:
 * leaving the viewer closes only XFiles' HTTP server, not the selected receiver route.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ImageCastController(
    context: Context,
    entries: List<XEntry>,
) : Closeable {
    private val appContext = context.applicationContext
    private val relay = CastMediaRelay(appContext, entries.filter(::canCastRelayImage))
    private val sessions = CastContext.getSharedInstance(appContext).sessionManager
    private var selected: XEntry? = null
    private var sentId: String? = null
    private var sentSession: CastSession? = null
    private var closed = false

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = reconnect(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            showError(R.string.cast_image_failed)
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = reset()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = reconnect(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = reset()
        override fun onSessionSuspended(session: CastSession, reason: Int) = reset()
    }

    init {
        sessions.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    fun select(entry: XEntry) {
        if (closed) return
        selected = entry
        sessions.currentCastSession?.takeIf { it.isConnected }?.let { send(entry, it) }
    }

    private fun reconnect(session: CastSession) {
        reset()
        selected?.let { send(it, session) }
    }

    private fun reset() {
        sentId = null
        sentSession = null
    }

    private fun send(entry: XEntry, session: CastSession) {
        if (closed || !session.isConnected) return
        if (!shouldSendCastImage(sentId, entry.id, sentSession !== session)) return
        val mime = castImageMimeType(entry)
        if (mime == null) {
            showError(R.string.cast_image_unsupported)
            return
        }
        val url = relay.imageUrlFor(entry)
        if (url == null) {
            showError(R.string.cast_image_unavailable)
            return
        }
        val client = session.remoteMediaClient
        if (client == null) {
            showError(R.string.cast_image_failed)
            return
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
            putString(MediaMetadata.KEY_TITLE, entry.name)
        }
        val media = MediaInfo.Builder(url.toString())
            .setStreamType(MediaInfo.STREAM_TYPE_NONE)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .build()

        // The old video session must not keep advertising playback after a photo replaces it.
        CastPlaybackSessionManager.stopForImageCast()
        relay.prewarm(listOf(entry.id))
        sentSession = session
        sentId = entry.id
        client.load(request).setResultCallback { result ->
            if (!result.status.isSuccess && !closed && sentId == entry.id) {
                sentId = null
                showError(R.string.cast_image_failed)
            }
        }
    }

    private fun showError(message: Int) {
        if (!closed) Toast.makeText(appContext, appContext.getString(message), Toast.LENGTH_LONG).show()
    }

    override fun close() {
        if (closed) return
        closed = true
        sessions.removeSessionManagerListener(sessionListener, CastSession::class.java)
        relay.close()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun EditionImageCastHost(
    items: List<XEntry>,
    selected: XEntry,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(context, items) { ImageCastController(context, items) }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    LaunchedEffect(controller, selected.id) { controller.select(selected) }
    content()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun EditionImageCastButton() {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        MediaRouteButton()
    }
}

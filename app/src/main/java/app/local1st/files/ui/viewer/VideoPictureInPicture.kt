package app.local1st.files.ui.viewer

import android.app.PendingIntent
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.app.PictureInPictureParamsCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import app.local1st.files.R

/**
 * Keeps video playback eligible for Android picture-in-picture while the video player is active.
 * Android 12+ uses system auto-enter for a smooth Home gesture transition; Android 8-11 enters
 * PiP from the Activity's user-leave callback. Playback continues only while PiP is actually
 * active; if the Activity reaches the background without entering PiP, playback is paused so
 * audio cannot continue invisibly behind the launcher or another app.
 * This uses Android's system PiP and therefore needs no draw-over-other-apps permission.
 */
@Composable
internal fun VideoPictureInPicture(
    player: Player,
    playing: Boolean,
    title: String,
    onModeChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    val latestPlaying by rememberUpdatedState(playing)
    val latestTitle by rememberUpdatedState(title)
    val latestOnModeChanged by rememberUpdatedState(onModeChanged)
    val pipActions = remember(context) { pipRemoteActions(context) }
    var aspectRatio by remember(player) { mutableStateOf(player.safePipAspectRatio()) }

    DisposableEffect(context, player) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PIP_REPLAY_5 -> player.seekByPip(-PIP_SEEK_STEP_MS)
                    ACTION_PIP_FORWARD_5 -> player.seekByPip(PIP_SEEK_STEP_MS)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_REPLAY_5)
            addAction(ACTION_PIP_FORWARD_5)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspectRatio = safePipAspectRatio(videoSize.width, videoSize.height)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(activity, playing, title, aspectRatio, pipActions) {
        activity?.setPictureInPictureParams(
            pipParams(
                enabled = playing,
                title = title,
                aspectRatio = aspectRatio,
                actions = pipActions,
            ),
        )
    }

    DisposableEffect(activity, player) {
        if (activity == null) {
            onDispose { }
        } else {
            var wasInPip = activity.isInPictureInPictureMode
            var pipExitPending = false
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        // A PiP -> fullscreen transition also reports PiP=false. Reaching RESUME
                        // means the user expanded the window rather than dismissed it.
                        pipExitPending = false
                    }
                    Lifecycle.Event.ON_STOP -> {
                        // Some Android versions dispatch ON_STOP before the PiP=false callback.
                        // Keep playback only when the Activity is still genuinely in PiP.
                        if (pipExitPending || !activity.isInPictureInPictureMode) {
                            player.pause()
                        }
                    }
                    Lifecycle.Event.ON_DESTROY -> player.pause()
                    else -> Unit
                }
            }
            activity.lifecycle.addObserver(lifecycleObserver)

            if (!activity.supportsPip()) {
                onDispose {
                    activity.lifecycle.removeObserver(lifecycleObserver)
                }
            } else {
                val leaveListener = Runnable {
                    // Android 12+ auto-enters from setEnabled(true). Older versions still need an
                    // explicit request when the user leaves the Activity.
                    if (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                        latestPlaying &&
                        !activity.isInPictureInPictureMode
                    ) {
                        activity.enterPictureInPictureMode(
                            pipParams(
                                enabled = true,
                                title = latestTitle,
                                aspectRatio = aspectRatio,
                                actions = pipActions,
                            ),
                        )
                    }
                }
                val modeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
                    val inPip = info.isInPictureInPictureMode
                    if (wasInPip && !inPip) {
                        pipExitPending = true
                        // PiP dismissal can report ON_STOP first and mode=false second. If the
                        // Activity is already stopped, there will be no later lifecycle event to
                        // catch the transition, so pause here as well.
                        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            player.pause()
                        }
                    } else if (inPip) {
                        pipExitPending = false
                    }
                    wasInPip = inPip
                    latestOnModeChanged(inPip)
                }

                activity.addOnUserLeaveHintListener(leaveListener)
                activity.addOnPictureInPictureModeChangedListener(modeListener)
                latestOnModeChanged(activity.isInPictureInPictureMode)

                onDispose {
                    activity.lifecycle.removeObserver(lifecycleObserver)
                    activity.removeOnUserLeaveHintListener(leaveListener)
                    activity.removeOnPictureInPictureModeChangedListener(modeListener)
                    activity.setPictureInPictureParams(pipParams(enabled = false))
                }
            }
        }
    }
}

private fun pipParams(
    enabled: Boolean,
    title: String? = null,
    aspectRatio: Rational = Rational(16, 9),
    actions: List<RemoteAction> = emptyList(),
): PictureInPictureParamsCompat =
    PictureInPictureParamsCompat.Builder()
        .setEnabled(enabled)
        .setAspectRatio(aspectRatio)
        .setSeamlessResizeEnabled(true)
        .setActions(actions)
        .apply {
            if (!title.isNullOrBlank()) setTitle(title)
        }
        .build()

private fun pipRemoteActions(context: Context): List<RemoteAction> = listOf(
    pipRemoteAction(
        context = context,
        iconRes = R.drawable.ic_pip_replay_5,
        label = "-5s",
        action = ACTION_PIP_REPLAY_5,
        requestCode = PIP_REPLAY_REQUEST_CODE,
    ),
    pipRemoteAction(
        context = context,
        iconRes = R.drawable.ic_pip_forward_5,
        label = "+5s",
        action = ACTION_PIP_FORWARD_5,
        requestCode = PIP_FORWARD_REQUEST_CODE,
    ),
)

private fun pipRemoteAction(
    context: Context,
    iconRes: Int,
    label: String,
    action: String,
    requestCode: Int,
): RemoteAction {
    val intent = Intent(action).setPackage(context.packageName)
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return RemoteAction(
        Icon.createWithResource(context, iconRes),
        label,
        label,
        pendingIntent,
    )
}

private fun Player.seekByPip(deltaMs: Long) {
    val knownDuration = duration.takeIf { it != C.TIME_UNSET && it > 0L }
    val target = (currentPosition + deltaMs).coerceAtLeast(0L)
    seekTo(knownDuration?.let { target.coerceAtMost(it) } ?: target)
}

private fun Player.safePipAspectRatio(): Rational =
    safePipAspectRatio(videoSize.width, videoSize.height)

private fun safePipAspectRatio(width: Int, height: Int): Rational {
    if (width <= 0 || height <= 0) return Rational(16, 9)
    val ratio = width.toDouble() / height.toDouble()
    return if (ratio in (1.0 / MAX_PIP_ASPECT_RATIO)..MAX_PIP_ASPECT_RATIO) {
        Rational(width, height)
    } else {
        Rational(16, 9)
    }
}

private fun ComponentActivity.supportsPip(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

private const val ACTION_PIP_REPLAY_5 = "app.local1st.files.action.PIP_REPLAY_5"
private const val ACTION_PIP_FORWARD_5 = "app.local1st.files.action.PIP_FORWARD_5"
private const val PIP_REPLAY_REQUEST_CODE = 501
private const val PIP_FORWARD_REQUEST_CODE = 502
private const val PIP_SEEK_STEP_MS = 5_000L
private const val MAX_PIP_ASPECT_RATIO = 2.39

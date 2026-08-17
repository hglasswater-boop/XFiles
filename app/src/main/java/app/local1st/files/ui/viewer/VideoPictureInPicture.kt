package app.local1st.files.ui.viewer

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
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
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * Keeps video playback eligible for Android picture-in-picture while the video player is active.
 * Android 12+ uses system auto-enter for a smooth Home gesture transition; Android 8-11 enters
 * PiP from the Activity's user-leave callback. The ExoPlayer itself is deliberately not paused
 * when the Activity leaves the foreground, so playback continues in the floating window.
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
    var aspectRatio by remember(player) { mutableStateOf(player.safePipAspectRatio()) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                aspectRatio = safePipAspectRatio(videoSize.width, videoSize.height)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(activity, playing, title, aspectRatio) {
        activity?.setPictureInPictureParams(
            pipParams(
                enabled = playing,
                title = title,
                aspectRatio = aspectRatio,
            ),
        )
    }

    DisposableEffect(activity) {
        if (activity == null || !activity.supportsPip()) {
            onDispose { }
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
                        ),
                    )
                }
            }
            val modeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
                latestOnModeChanged(info.isInPictureInPictureMode)
            }

            activity.addOnUserLeaveHintListener(leaveListener)
            activity.addOnPictureInPictureModeChangedListener(modeListener)
            latestOnModeChanged(activity.isInPictureInPictureMode)

            onDispose {
                activity.removeOnUserLeaveHintListener(leaveListener)
                activity.removeOnPictureInPictureModeChangedListener(modeListener)
                activity.setPictureInPictureParams(pipParams(enabled = false))
            }
        }
    }
}

private fun pipParams(
    enabled: Boolean,
    title: String? = null,
    aspectRatio: Rational = Rational(16, 9),
): PictureInPictureParamsCompat =
    PictureInPictureParamsCompat.Builder()
        .setEnabled(enabled)
        .setAspectRatio(aspectRatio)
        .setSeamlessResizeEnabled(true)
        .apply {
            if (!title.isNullOrBlank()) setTitle(title)
        }
        .build()

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

private const val MAX_PIP_ASPECT_RATIO = 2.39

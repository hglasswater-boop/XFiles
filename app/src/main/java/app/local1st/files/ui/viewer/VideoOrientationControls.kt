package app.local1st.files.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ScreenLockRotation
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.local1st.files.ui.components.TooltipIconButton

internal enum class PlayerOrientationMode {
    AUTO,
    LOCKED,
    LANDSCAPE,
}

/**
 * Owns the player orientation request for the whole lifetime of VideoPlayerScreen.
 *
 * This must be remembered outside the auto-hiding controls panel. Otherwise hiding the panel
 * disposes the orientation effect and immediately restores the Activity's previous orientation.
 */
internal class VideoOrientationController(
    private val activity: Activity?,
) {
    var mode by mutableStateOf(PlayerOrientationMode.AUTO)
        private set

    fun enterPlayer() {
        applyRequestedOrientation()
    }

    fun selectMode(newMode: PlayerOrientationMode) {
        mode = newMode
        applyRequestedOrientation()
    }

    fun restore(requestedOrientation: Int) {
        activity?.requestedOrientation = requestedOrientation
    }

    private fun applyRequestedOrientation() {
        activity?.requestedOrientation = when (mode) {
            PlayerOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientationMode.LOCKED -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            PlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}

/**
 * Player-lifetime orientation controller.
 *
 * Video playback follows the device sensor by default, independent of the system rotation lock.
 * The chosen mode remains active even while the playback controls are hidden. Only leaving the
 * player restores the Activity's previous orientation request.
 */
@Composable
internal fun rememberVideoOrientationController(): VideoOrientationController {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val previousRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val controller = remember(activity) { VideoOrientationController(activity) }

    DisposableEffect(activity, controller) {
        controller.enterPlayer()
        onDispose {
            controller.restore(previousRequestedOrientation)
        }
    }

    return controller
}

/**
 * Compact orientation buttons overlaid at the right edge of the playback-button row.
 * Orientation ownership lives in [controller], so hiding the controls never resets the mode.
 */
@Composable
internal fun VideoOrientationQuickControls(
    controller: VideoOrientationController,
    onInteraction: () -> Unit,
) {
    val mode = controller.mode

    fun applyMode(newMode: PlayerOrientationMode) {
        controller.selectMode(newMode)
        onInteraction()
    }

    TooltipIconButton(
        if (mode == PlayerOrientationMode.LOCKED) "向きロックを解除" else "現在の向きでロック",
        if (mode == PlayerOrientationMode.LOCKED) {
            Icons.Outlined.ScreenRotation
        } else {
            Icons.Outlined.ScreenLockRotation
        },
    ) {
        applyMode(
            if (mode == PlayerOrientationMode.LOCKED) {
                PlayerOrientationMode.AUTO
            } else {
                PlayerOrientationMode.LOCKED
            },
        )
    }

    TooltipIconButton(
        if (mode == PlayerOrientationMode.LANDSCAPE) "自動回転に戻す" else "横向きにする",
        if (mode == PlayerOrientationMode.LANDSCAPE) {
            Icons.Outlined.ScreenRotation
        } else {
            Icons.Outlined.StayCurrentLandscape
        },
    ) {
        applyMode(
            if (mode == PlayerOrientationMode.LANDSCAPE) {
                PlayerOrientationMode.AUTO
            } else {
                PlayerOrientationMode.LANDSCAPE
            },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

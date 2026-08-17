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

private enum class PlayerOrientationMode {
    AUTO,
    LOCKED,
    LANDSCAPE,
}

/**
 * Player-only orientation controls.
 *
 * Video playback follows the device sensor by default, independent of the system rotation lock.
 * The user can lock the currently displayed orientation or force a stable landscape orientation.
 * When the player leaves composition the Activity's previous orientation request is restored so
 * browsing outside the player keeps the app's original behaviour.
 */
@Composable
internal fun VideoOrientationQuickControls(
    onInteraction: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val previousRequestedOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    var mode by remember { mutableStateOf(PlayerOrientationMode.AUTO) }

    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = previousRequestedOrientation
        }
    }

    fun applyMode(newMode: PlayerOrientationMode) {
        mode = newMode
        activity?.requestedOrientation = when (newMode) {
            PlayerOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            PlayerOrientationMode.LOCKED -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            PlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
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
        if (mode == PlayerOrientationMode.LANDSCAPE) "自動回転に戻す" else "横向きに固定",
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

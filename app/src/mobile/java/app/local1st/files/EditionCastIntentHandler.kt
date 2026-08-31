package app.local1st.files

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import app.local1st.files.core.cast.CastPlaybackKeepAliveService
import app.local1st.files.ui.viewer.CastPlaybackSessionManager

@androidx.annotation.OptIn(UnstableApi::class)
internal fun handleEditionIntent(intent: Intent): Boolean {
    if (intent.action != CastPlaybackKeepAliveService.ACTION_OPEN_PLAYBACK) return false
    CastPlaybackSessionManager.requestOpenControls()
    return true
}

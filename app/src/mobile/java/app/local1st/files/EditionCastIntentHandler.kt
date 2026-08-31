package app.local1st.files

import android.content.Intent
import app.local1st.files.core.cast.CastPlaybackKeepAliveService
import app.local1st.files.ui.viewer.CastPlaybackSessionManager

internal fun handleEditionIntent(intent: Intent): Boolean {
    if (intent.action != CastPlaybackKeepAliveService.ACTION_OPEN_PLAYBACK) return false
    CastPlaybackSessionManager.requestOpenControls()
    return true
}

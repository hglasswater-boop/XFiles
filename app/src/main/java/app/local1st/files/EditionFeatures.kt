package app.local1st.files

import android.content.Context
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi

@androidx.annotation.OptIn(UnstableApi::class)
internal fun initializeEditionFeatures(context: Context) {
    Cast.getSingletonInstance(context).initialize()
}

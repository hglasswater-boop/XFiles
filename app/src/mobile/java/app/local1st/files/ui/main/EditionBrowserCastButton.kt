package app.local1st.files.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton

/** Cast entry point rendered as a peer of the browser toolbar actions. */
@Composable
internal fun EditionBrowserCastButton(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(48.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            MediaRouteButton()
        }
    }
}

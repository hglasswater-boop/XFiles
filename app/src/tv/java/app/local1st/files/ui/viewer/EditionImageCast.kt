package app.local1st.files.ui.viewer

import androidx.compose.runtime.Composable
import app.local1st.files.core.fs.XEntry

/** TV is a receiver edition; only Mobile offers a Cast sender. */
@Composable
internal fun EditionImageCastHost(
    items: List<XEntry>,
    selected: XEntry,
    content: @Composable () -> Unit,
) {
    content()
}

@Composable
internal fun EditionImageCastButton() = Unit

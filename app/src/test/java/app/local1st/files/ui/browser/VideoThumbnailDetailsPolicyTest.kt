package app.local1st.files.ui.browser

import app.local1st.files.core.prefs.ThumbnailSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbnailDetailsPolicyTest {
    @Test
    fun detailsShortcutFitsOnlyLargeAndExtraLargeVideoThumbnails() {
        assertFalse(shouldShowVideoThumbnailDetails(true, ThumbnailSize.SMALL, true))
        assertFalse(shouldShowVideoThumbnailDetails(true, ThumbnailSize.MEDIUM, true))
        assertTrue(shouldShowVideoThumbnailDetails(true, ThumbnailSize.LARGE, true))
        assertTrue(shouldShowVideoThumbnailDetails(true, ThumbnailSize.EXTRA_LARGE, true))
    }

    @Test
    fun detailsShortcutIsAbsentForNonVideoOrDisabledRows() {
        assertFalse(shouldShowVideoThumbnailDetails(false, ThumbnailSize.EXTRA_LARGE, true))
        assertFalse(shouldShowVideoThumbnailDetails(true, ThumbnailSize.EXTRA_LARGE, false))
    }
}

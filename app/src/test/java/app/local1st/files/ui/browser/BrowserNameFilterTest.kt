package app.local1st.files.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNameFilterTest {
    @Test
    fun substringMatchIsCaseInsensitive() {
        assertTrue(browserNameMatches("Summer_Video.MP4", "video"))
        assertTrue(browserNameMatches("Summer_Video.MP4", "SUMMER"))
        assertFalse(browserNameMatches("Summer_Video.MP4", "winter"))
    }

    @Test
    fun wildcardMatchUsesWholeFilename() {
        assertTrue(browserNameMatches("movie-001.mp4", "movie-*.mp4"))
        assertTrue(browserNameMatches("ABC-123.mkv", "abc-???.mkv"))
        assertFalse(browserNameMatches("prefix-movie-001.mp4", "movie-*.mp4"))
    }

    @Test
    fun wildcardEscapesRegexCharacters() {
        assertTrue(browserNameMatches("sample[1].mp4", "sample[?].mp4"))
        assertFalse(browserNameMatches("sample11.mp4", "sample[?].mp4"))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(browserNameMatches("anything.txt", "   "))
    }
}

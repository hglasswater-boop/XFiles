package app.local1st.files.core.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameSearchQueryTest {
    @Test
    fun wildcardOnlyQueries_areSearchableBelowPlainTextMinimum() {
        assertTrue(FilenameSearchQuery.isSearchable("*"))
        assertTrue(FilenameSearchQuery.isSearchable("?"))
        assertFalse(FilenameSearchQuery.isSearchable(""))
        assertFalse(FilenameSearchQuery.isSearchable("a"))
        assertTrue(FilenameSearchQuery.isSearchable("ab"))
    }

    @Test
    fun plainText_remainsCaseInsensitiveSubstringMatch() {
        val matcher = FilenameSearchQuery.matcher("port")

        assertTrue(matcher("Report.MP4"))
        assertFalse(matcher("photo.jpg"))
    }

    @Test
    fun extensionSearch_matchesWithOrWithoutLeadingDot() {
        val bareExtension = FilenameSearchQuery.matcher("mp4")
        val dottedExtension = FilenameSearchQuery.matcher(".mp4")

        assertTrue(bareExtension("movie.mp4"))
        assertTrue(bareExtension("MOVIE.MP4"))
        assertTrue(dottedExtension("movie.mp4"))
        assertTrue(dottedExtension("MOVIE.MP4"))
        assertFalse(dottedExtension("movie.mp4.bak"))
    }

    @Test
    fun starWildcard_matchesWholeFilenameCaseInsensitively() {
        val matcher = FilenameSearchQuery.matcher("*.mp4")

        assertTrue(matcher("movie.mp4"))
        assertTrue(matcher("MOVIE.MP4"))
        assertFalse(matcher("movie.mp4.bak"))
        assertFalse(matcher("movie.mkv"))
    }

    @Test
    fun questionWildcard_matchesExactlyOneCharacter() {
        val matcher = FilenameSearchQuery.matcher("photo?.jpg")

        assertTrue(matcher("photo1.jpg"))
        assertTrue(matcher("photoA.JPG"))
        assertFalse(matcher("photo.jpg"))
        assertFalse(matcher("photo12.jpg"))
    }

    @Test
    fun regexMetacharacters_areLiteralInsideWildcardQuery() {
        val matcher = FilenameSearchQuery.matcher("file[1].*")

        assertTrue(matcher("file[1].txt"))
        assertFalse(matcher("file1.txt"))
    }
}

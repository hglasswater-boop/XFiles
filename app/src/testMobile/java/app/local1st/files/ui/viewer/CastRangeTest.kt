package app.local1st.files.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRangeTest {
    @Test
    fun fullRequestUsesWholeFile() {
        assertEquals(ByteRange(0, 999, partial = false), resolveRange(null, 1000))
    }

    @Test
    fun openEndedRangeRunsToEnd() {
        assertEquals(ByteRange(250, 999, partial = true), resolveRange("bytes=250-", 1000))
    }

    @Test
    fun suffixRangeUsesTail() {
        assertEquals(ByteRange(900, 999, partial = true), resolveRange("bytes=-100", 1000))
    }

    @Test
    fun rangeEndIsClamped() {
        assertEquals(ByteRange(900, 999, partial = true), resolveRange("bytes=900-5000", 1000))
    }

    @Test
    fun impossibleRangeIsRejected() {
        assertNull(resolveRange("bytes=1000-", 1000))
        assertNull(resolveRange("items=0-10", 1000))
    }
}

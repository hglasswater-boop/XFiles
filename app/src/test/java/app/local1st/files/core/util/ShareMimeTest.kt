package app.local1st.files.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareMimeTest {
    @Test
    fun `multiple video types stay video share`() {
        assertEquals(
            "video/*",
            commonShareMimeType(listOf("video/mp4", "video/x-matroska")),
        )
    }

    @Test
    fun `same exact mime stays exact`() {
        assertEquals(
            "video/mp4",
            commonShareMimeType(listOf("video/mp4", "video/mp4")),
        )
    }

    @Test
    fun `mixed top level types fall back to wildcard`() {
        assertEquals(
            "*/*",
            commonShareMimeType(listOf("video/mp4", "image/jpeg")),
        )
    }

    @Test
    fun `unknown type forces wildcard`() {
        assertEquals(
            "*/*",
            commonShareMimeType(listOf("video/mp4", null)),
        )
    }
}

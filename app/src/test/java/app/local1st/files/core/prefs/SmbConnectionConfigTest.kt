package app.local1st.files.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFailsWith
import org.junit.Test

class SmbConnectionConfigTest {
    @Test
    fun `share slash path is split into share and base path`() {
        val config = smbConnectionFromInput(
            id = "test",
            name = "",
            host = "192.168.1.10",
            sharePath = "video_a/actress",
            username = "user",
            domain = "",
        )

        assertEquals("video_a", config.share)
        assertEquals("actress", config.basePath)
        assertEquals("video_a/actress", config.sharePath)
        assertEquals("video_a/actress", config.name)
        assertEquals("\\\\192.168.1.10\\video_a\\actress", config.uncPath)
    }

    @Test
    fun `backslashes and repeated separators are accepted`() {
        val config = smbConnectionFromInput(
            id = "test",
            name = "NAS",
            host = "nas.local",
            sharePath = "\\video_a\\actress\\works\\",
            username = "",
            domain = "",
        )

        assertEquals("video_a", config.share)
        assertEquals("actress/works", config.basePath)
        assertEquals("NAS", config.name)
    }

    @Test
    fun `plain share remains share root`() {
        val config = smbConnectionFromInput(
            id = "test",
            name = "",
            host = "nas.local",
            sharePath = "video_a",
            username = "",
            domain = "",
        )

        assertEquals("video_a", config.share)
        assertEquals("", config.basePath)
    }

    @Test
    fun `dot navigation is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            smbConnectionFromInput(
                id = "test",
                name = "",
                host = "nas.local",
                sharePath = "video_a/../secret",
                username = "",
                domain = "",
            )
        }
    }
}

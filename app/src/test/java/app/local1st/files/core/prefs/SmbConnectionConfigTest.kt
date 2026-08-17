package app.local1st.files.core.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbConnectionConfigTest {
    @Test
    fun `share slash path is split into share and base path`() {
        val config = smbConnectionFromInput(
            id = "test",
            name = "",
            host = "192.168.1.10",
            sharePath = "share/folder",
            username = "user",
            domain = "",
        )

        assertEquals("share", config.share)
        assertEquals("folder", config.basePath)
        assertEquals("share/folder", config.sharePath)
        assertEquals("share/folder", config.name)
        assertEquals("\\\\192.168.1.10\\share\\folder", config.uncPath)
    }

    @Test
    fun `backslashes and repeated separators are accepted`() {
        val config = smbConnectionFromInput(
            id = "test",
            name = "NAS",
            host = "nas.local",
            sharePath = "\\share\\folder\\subfolder\\",
            username = "",
            domain = "",
        )

        assertEquals("share", config.share)
        assertEquals("folder/subfolder", config.basePath)
        assertEquals("NAS", config.name)
    }

    @Test
    fun `plain share remains share root`() {
        val config = smbConnectionFromInput(
            id = "test",
            name = "",
            host = "nas.local",
            sharePath = "share",
            username = "",
            domain = "",
        )

        assertEquals("share", config.share)
        assertEquals("", config.basePath)
    }

    @Test
    fun `dot navigation is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            smbConnectionFromInput(
                id = "test",
                name = "",
                host = "nas.local",
                sharePath = "share/../secret",
                username = "",
                domain = "",
            )
        }
    }
    @Test
    fun `friendly SMB paths never expose connection id`() {
        val config = smbConnectionFromInput(
            id = "123e4567-e89b-12d3-a456-426614174000",
            name = "Home NAS",
            host = "nas.local",
            sharePath = "share/start",
            username = "",
            domain = "",
        )
        val lookup: (String) -> SmbConnectionConfig? = { if (it == config.id) config else null }
        val id = "smb://${config.id}/photos/2026"

        assertEquals("\\\\nas.local\\share\\start\\photos\\2026", smbDisplayPath(id, lookup))
        assertEquals("Home NAS / photos/2026", smbDisplayLabelPath(id, lookup))
        assertEquals("SMB / photos/2026", smbDisplayLabelPath("smb://missing/photos/2026") { null })
        assertEquals("SMB\\photos\\2026", smbDisplayPath("smb://missing/photos/2026") { null })
    }
}

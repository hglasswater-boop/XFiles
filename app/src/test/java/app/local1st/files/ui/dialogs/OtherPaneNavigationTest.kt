package app.local1st.files.ui.dialogs

import app.local1st.files.core.prefs.ContextMenuOrderSettings
import org.junit.Assert.assertTrue
import org.junit.Test

class OtherPaneNavigationTest {
    @Test
    fun `open in other pane participates in context menu ordering`() {
        assertTrue(
            ContextMenuOrderSettings.OPEN_IN_OTHER_PANE in ContextMenuOrderSettings.DEFAULT_ORDER,
        )
    }
}

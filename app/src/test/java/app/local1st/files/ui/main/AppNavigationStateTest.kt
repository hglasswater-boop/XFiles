package app.local1st.files.ui.main

import app.local1st.files.core.fs.XEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun browserIsPermanentRoot() {
        val navigation = AppNavigationState()

        assertFalse(navigation.navigateBack())
        assertEquals(1, navigation.backStack.size)
        assertSame(AppScreen.Browser, navigation.backStack.single().screen)
    }

    @Test
    fun navigationPushesAndPopsDestinations() {
        val navigation = AppNavigationState()
        val settings = navigation.navigate(AppScreen.Settings)
        val search = navigation.navigate(AppScreen.Search(searchRoot("example")))

        assertEquals(listOf(0L, settings.id, search.id), navigation.backStack.map { it.id })
        assertTrue(navigation.navigateBack(search.id))
        assertSame(AppScreen.Settings, navigation.backStack.last().screen)
        assertTrue(navigation.navigateBack(settings.id))
        assertSame(AppScreen.Browser, navigation.backStack.last().screen)
    }

    @Test
    fun staleCallbackCannotPopNewDestination() {
        val navigation = AppNavigationState()
        val settings = navigation.navigate(AppScreen.Settings)
        val search = navigation.navigate(AppScreen.Search(searchRoot("example")))

        assertFalse(navigation.navigateBack(settings.id))
        assertEquals(search.id, navigation.backStack.last().id)
    }

    @Test
    fun replaceTopKeepsPreviousDestinationAndGetsFreshIdentity() {
        val navigation = AppNavigationState()
        val first = navigation.navigate(AppScreen.Search(searchRoot("one")))
        val replacement = navigation.navigate(
            AppScreen.Search(searchRoot("two")),
            replaceTop = true,
        )

        assertEquals(2, navigation.backStack.size)
        assertTrue(replacement.id > first.id)
        assertEquals("two", (navigation.backStack.last().screen as AppScreen.Search).root.name)
    }

    private fun searchRoot(name: String) = XEntry(
        id = "file:///tmp/$name",
        name = name,
        isDir = true,
    )
}

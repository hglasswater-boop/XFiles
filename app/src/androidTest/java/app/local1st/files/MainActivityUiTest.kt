package app.local1st.files

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    private lateinit var device: UiDevice
    private lateinit var packageName: String

    @Before
    fun grantPermissionsNeededByBrowser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        packageName = instrumentation.targetContext.packageName

        // The browser is intentionally gated behind All Files Access. Grant it through the
        // instrumentation shell so CI reaches the real browser instead of the permission page.
        device.executeShellCommand(
            "appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.executeShellCommand(
                "pm grant $packageName android.permission.POST_NOTIFICATIONS",
            )
        }
    }

    @Test
    fun searchCanBeOpenedTypedClearedAndClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val searchLabel = context.getString(R.string.search)
        val closeSearchLabel = context.getString(R.string.close_search)
        val clearQueryLabel = context.getString(R.string.clear_query)

        ActivityScenario.launch(MainActivity::class.java).use {
            requireObject(By.desc(searchLabel), "Search toolbar button").click()

            requireObject(By.desc(closeSearchLabel), "Close search button")
            val input = requireObject(
                By.clazz("android.widget.EditText"),
                "Search text field",
            )
            input.setText("xfiles")

            requireObject(By.desc(clearQueryLabel), "Clear query button").click()
            check(
                device.wait(Until.gone(By.desc(clearQueryLabel)), UI_TIMEOUT_MS),
            ) { "Search query did not clear" }

            requireObject(By.desc(closeSearchLabel), "Close search button").click()
            requireObject(By.desc(searchLabel), "Search toolbar button after closing search")
        }
    }

    @Test
    fun settingsCanBeOpenedFromMoreAndReturnedToBrowser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val moreLabel = context.getString(R.string.more)
        val settingsLabel = context.getString(R.string.settings)
        val backLabel = context.getString(R.string.back)

        ActivityScenario.launch(MainActivity::class.java).use {
            requireObject(By.desc(moreLabel), "More toolbar button").click()

            findTextInCurrentScrollable(settingsLabel).click()
            requireObject(By.desc(backLabel), "Settings back button")
            assertNotNull(
                "Settings title is not visible",
                device.wait(Until.findObject(By.text(settingsLabel)), UI_TIMEOUT_MS),
            )

            requireObject(By.desc(backLabel), "Settings back button").click()
            requireObject(By.desc(moreLabel), "More toolbar button after returning")
        }
    }

    private fun requireObject(
        selector: BySelector,
        label: String,
        timeoutMs: Long = UI_TIMEOUT_MS,
    ): UiObject2 = device.wait(Until.findObject(selector), timeoutMs)
        ?: throw AssertionError("Timed out waiting for $label")

    private fun findTextInCurrentScrollable(text: String): UiObject2 {
        repeat(MAX_SCROLL_ATTEMPTS) {
            device.wait(Until.findObject(By.text(text)), SHORT_WAIT_MS)?.let { return it }

            val scrollable = device.findObjects(By.scrollable(true)).lastOrNull()
                ?: throw AssertionError("No scrollable container while looking for '$text'")
            runCatching { scrollable.scroll(Direction.DOWN, 0.8f) }
            device.waitForIdle()
        }
        throw AssertionError("Could not find '$text' after scrolling")
    }

    private companion object {
        const val UI_TIMEOUT_MS = 20_000L
        const val SHORT_WAIT_MS = 800L
        const val MAX_SCROLL_ATTEMPTS = 8
    }
}

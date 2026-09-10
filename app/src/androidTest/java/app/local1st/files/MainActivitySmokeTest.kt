package app.local1st.files

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @Test
    fun mobileAppLaunchesMainActivity() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.local1st.files", targetContext.packageName)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertNotNull(activity.findViewById<View>(android.R.id.content))
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
        }
    }

    @Test
    fun mobileAppSurvivesBackgroundForegroundRoundTrip() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
        }
    }
}

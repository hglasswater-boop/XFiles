package app.local1st.files

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.InputStreamReader
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantBrowserPermissionsRule())
        .around(composeRule)

    @Test
    fun searchCanBeOpenedTypedClearedAndClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val searchLabel = context.getString(R.string.search)
        val closeSearchLabel = context.getString(R.string.close_search)
        val clearQueryLabel = context.getString(R.string.clear_query)

        composeRule.onNodeWithContentDescription(searchLabel)
            .assertExists()
            .performClick()

        composeRule.onNodeWithContentDescription(closeSearchLabel).assertExists()
        composeRule.onNode(hasSetTextAction())
            .assertExists()
            .performTextInput("xfiles")

        composeRule.onNodeWithContentDescription(clearQueryLabel)
            .assertExists()
            .performClick()
        composeRule.onNodeWithContentDescription(clearQueryLabel).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(closeSearchLabel).performClick()
        composeRule.onNodeWithContentDescription(searchLabel).assertExists()
    }

    @Test
    fun settingsCanBeOpenedFromMoreAndReturnedToBrowser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val moreLabel = context.getString(R.string.more)
        val settingsLabel = context.getString(R.string.settings)
        val backLabel = context.getString(R.string.back)

        composeRule.onNodeWithContentDescription(moreLabel)
            .assertExists()
            .performClick()

        composeRule.onNode(hasText(settingsLabel) and hasClickAction())
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithContentDescription(backLabel).assertExists()
        composeRule.onNodeWithText(settingsLabel).assertExists()

        composeRule.onNodeWithContentDescription(backLabel).performClick()
        composeRule.onNodeWithContentDescription(moreLabel).assertExists()
    }

    /**
     * Runs before the Compose activity rule so [MainActivity] sees the same permissions as a
     * normal user who already completed storage onboarding.
     */
    private class GrantBrowserPermissionsRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val packageName = instrumentation.targetContext.packageName

                    shell(
                        "appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow",
                    )
                    val storageOp = shell(
                        "appops get $packageName MANAGE_EXTERNAL_STORAGE",
                    )
                    check(storageOp.contains("allow", ignoreCase = true)) {
                        "Failed to grant All Files Access before launching MainActivity: $storageOp"
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        instrumentation.uiAutomation.grantRuntimePermission(
                            packageName,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    }

                    base.evaluate()
                }
            }

        private fun shell(command: String): String {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
            return descriptor.readFully()
        }

        private fun ParcelFileDescriptor.readFully(): String =
            ParcelFileDescriptor.AutoCloseInputStream(this).use { input ->
                BufferedReader(InputStreamReader(input)).use { reader -> reader.readText() }
            }
    }
}

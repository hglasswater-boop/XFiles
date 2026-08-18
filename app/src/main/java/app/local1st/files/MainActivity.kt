package app.local1st.files

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.SMB_POLL_INTERVAL_MS
import app.local1st.files.ui.browser.expandedSmbDirectoryIds
import app.local1st.files.ui.main.AppHost
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.theme.XFilesTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val incomingIntents = Channel<Intent>(Channel.BUFFERED)
    private val incomingIntentFlow = incomingIntents.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incomingIntents.trySend(intent)
        setContent {
            Root(incomingIntentFlow)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntents.trySend(intent)
    }
}

@Composable
private fun Root(incomingIntents: Flow<Intent>) {
    val themeMode by Graph.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by Graph.settings.dynamicColor.collectAsStateWithLifecycle(initialValue = true)

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    XFilesTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
        val vm: MainViewModel = viewModel()
        val lifecycleOwner = LocalLifecycleOwner.current

        LaunchedEffect(vm, incomingIntents) {
            incomingIntents.collect(vm::openExternalIntent)
        }
        LaunchedEffect(vm, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(SMB_POLL_INTERVAL_MS)
                    vm.panes.forEach { pane ->
                        // refresh() only replaces cached listings. Selection lives in a separate
                        // id set inside PaneController, so surviving checked rows stay checked.
                        expandedSmbDirectoryIds(pane.state.value).forEach(pane::refresh)
                    }
                }
            }
        }
        // AppHost keeps external viewers reachable without broad storage permission while making
        // every full-screen page a real destination instead of layering it over MainScreen.
        AppHost(vm)
    }
}

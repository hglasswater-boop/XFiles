package app.local1st.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.LocalDirectoryObserverSet
import app.local1st.files.ui.browser.SMB_POLL_INTERVAL_MS
import app.local1st.files.ui.browser.expandedLocalDirectoryIds
import app.local1st.files.ui.browser.expandedSmbDirectoryIds
import app.local1st.files.ui.main.AppHost
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.theme.XFilesTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    private val incomingIntents = Channel<Intent>(Channel.BUFFERED)
    private val incomingIntentFlow = incomingIntents.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
                LOCAL_NETWORK_PERMISSION_REQUEST,
            )
        }
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

    private companion object {
        const val LOCAL_NETWORK_PERMISSION_REQUEST = 1001
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
                val observers = LocalDirectoryObserverSet { change ->
                    vm.panes.forEach { pane ->
                        if (change.directoryId !in expandedLocalDirectoryIds(pane.state.value)) {
                            return@forEach
                        }

                        // A remove/move-out can be reflected immediately and also clears any
                        // checked/expanded descendants before the authoritative re-list arrives.
                        change.removedEntryId()?.let { pane.removeEntries(setOf(it)) }

                        if (change.removesWatchedDirectory()) {
                            pane.removeEntries(setOf(change.directoryId))
                            XId.parent(change.directoryId)?.let(pane::refresh)
                        } else {
                            pane.refresh(change.directoryId)
                        }
                    }
                }
                try {
                    val initialStates = vm.panes.map { it.state.value }
                    observers.sync(initialStates)

                    // FileObserver is intentionally stopped while backgrounded. Re-list once on
                    // resume so changes made while the app was stopped cannot leave stale rows.
                    vm.panes.forEach { pane ->
                        expandedLocalDirectoryIds(pane.state.value).forEach(pane::refresh)
                    }

                    combine(vm.panes[0].state, vm.panes[1].state) { left, right ->
                        listOf(left, right)
                    }.collect { states -> observers.sync(states) }
                } finally {
                    observers.close()
                }
            }
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

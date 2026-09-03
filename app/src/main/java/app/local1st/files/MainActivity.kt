package app.local1st.files

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.ThemeMode
import app.local1st.files.core.util.IntentUtils
import app.local1st.files.core.util.RemoteFileProvider
import app.local1st.files.core.util.SharedIntentResolver
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.LocalDirectoryObserverSet
import app.local1st.files.ui.browser.SMB_POLL_INTERVAL_MS
import app.local1st.files.ui.browser.expandedLocalDirectoryIds
import app.local1st.files.ui.browser.expandedSmbDirectoryIds
import app.local1st.files.ui.main.AppHost
import app.local1st.files.ui.main.MainViewModel
import app.local1st.files.ui.theme.XFilesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

private data class ExternalPickerRequest(val allowedExtensions: Set<String>)
private data class ExternalOutputRequest(val fileName: String, val mimeType: String)

class MainActivity : ComponentActivity() {

    private val incomingIntents = Channel<Intent>(Channel.BUFFERED)
    private val incomingIntentFlow = incomingIntents.receiveAsFlow()
    private val externalPickerRequest = MutableStateFlow<ExternalPickerRequest?>(null)
    private val externalOutputRequest = MutableStateFlow<ExternalOutputRequest?>(null)

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
        if (savedInstanceState == null) {
            forceStartupUpdateCheck()
            routeIntent(intent)
        }
        setContent {
            Root(
                incomingIntents = incomingIntentFlow,
                externalPickerRequest = externalPickerRequest,
                externalOutputRequest = externalOutputRequest,
                onReturnSelection = ::returnExternalPickerSelection,
                onReturnOutput = ::returnExternalOutputDestination,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent) {
        when (intent.action) {
            ACTION_PICK_FILES -> {
                val extensions = intent.getStringArrayExtra(EXTRA_ALLOWED_EXTENSIONS)
                    ?.map { it.trim().trimStart('.').lowercase() }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty()
                externalOutputRequest.value = null
                externalPickerRequest.value = ExternalPickerRequest(extensions)
            }
            ACTION_PICK_OUTPUT -> {
                val fileName = intent.getStringExtra(EXTRA_OUTPUT_NAME)?.trim().orEmpty()
                val mimeType = intent.getStringExtra(EXTRA_OUTPUT_MIME)?.trim().orEmpty()
                if (!isSafeOutputName(fileName)) {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                    return
                }
                externalPickerRequest.value = null
                externalOutputRequest.value = ExternalOutputRequest(
                    fileName = fileName,
                    mimeType = mimeType.ifBlank { "application/octet-stream" },
                )
            }
            else -> {
                externalPickerRequest.value = null
                externalOutputRequest.value = null
                incomingIntents.trySend(intent)
            }
        }
    }

    private fun returnExternalPickerSelection(entries: List<XEntry>) {
        if (entries.isEmpty()) return
        val result = runCatching { IntentUtils.pickerResult(this, entries) }.getOrNull() ?: return
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun returnExternalOutputDestination(directory: XEntry, request: ExternalOutputRequest) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    require(directory.isDir && directory.scheme == XId.SCHEME_SMB) {
                        "SMBフォルダを選択してください"
                    }
                    val fs = Graph.fsRegistry.forEntry(directory)
                    require(fs.canWrite(directory)) { "このフォルダには書き込めません" }
                    val finalId = XId.child(directory, request.fileName)
                    if (fs.stat(finalId) != null) {
                        throw FileAlreadyExistsException(request.fileName)
                    }
                    val partial = fs.createFile(
                        directory,
                        ".clipforge-partial-${UUID.randomUUID()}",
                    )
                    RemoteFileProvider.outputUriFor(
                        context = this@MainActivity,
                        partialEntry = partial,
                        finalName = request.fileName,
                        mimeType = request.mimeType,
                    )
                }
            }
            result.fold(
                onSuccess = { uri ->
                    val clips = ClipData.newRawUri(request.fileName, uri)
                    val output = Intent().apply {
                        data = uri
                        clipData = clips
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    setResult(Activity.RESULT_OK, output)
                    finish()
                },
                onFailure = { error ->
                    val message = if (error is FileAlreadyExistsException) {
                        "同名のファイルが既にあります: ${request.fileName}"
                    } else {
                        error.message ?: "保存先を準備できませんでした"
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun forceStartupUpdateCheck() {
        // Mobile and TV each keep their updater preferences in a flavor-specific store. Clearing
        // only the throttle timestamp preserves the user's auto-check toggle while making a fresh
        // app launch perform the requested update check instead of waiting up to 24 hours.
        UPDATE_PREFS.forEach { name ->
            getSharedPreferences(name, MODE_PRIVATE)
                .edit()
                .remove(LAST_AUTO_CHECK_KEY)
                .apply()
        }
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION_REQUEST = 1001
        const val LAST_AUTO_CHECK_KEY = "last_auto_check"
        const val ACTION_PICK_FILES = "app.local1st.files.action.PICK_FILES"
        const val ACTION_PICK_OUTPUT = "app.local1st.files.action.PICK_OUTPUT"
        const val EXTRA_ALLOWED_EXTENSIONS = "app.local1st.files.extra.ALLOWED_EXTENSIONS"
        const val EXTRA_OUTPUT_NAME = "app.local1st.files.extra.OUTPUT_NAME"
        const val EXTRA_OUTPUT_MIME = "app.local1st.files.extra.OUTPUT_MIME"
        val UPDATE_PREFS = arrayOf("mobile_self_update", "tv_self_update")

        fun isSafeOutputName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') &&
                !name.endsWith('.') && !name.endsWith(' ')
    }
}

@Composable
private fun Root(
    incomingIntents: Flow<Intent>,
    externalPickerRequest: StateFlow<ExternalPickerRequest?>,
    externalOutputRequest: StateFlow<ExternalOutputRequest?>,
    onReturnSelection: (List<XEntry>) -> Unit,
    onReturnOutput: (XEntry, ExternalOutputRequest) -> Unit,
) {
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
        val pickerRequest by externalPickerRequest.collectAsStateWithLifecycle()
        val outputRequest by externalOutputRequest.collectAsStateWithLifecycle()

        LaunchedEffect(vm, incomingIntents) {
            incomingIntents.collect { incoming ->
                if (handleEditionIntent(incoming)) return@collect
                when (incoming.action) {
                    Intent.ACTION_SEND,
                    Intent.ACTION_SEND_MULTIPLE,
                    -> {
                        val shared = withContext(Dispatchers.IO) {
                            runCatching { SharedIntentResolver.resolve(Graph.appContext, incoming) }
                        }
                        shared.fold(
                            onSuccess = { entries ->
                                if (entries.isNotEmpty()) {
                                    vm.chooseTransferDestination(move = false, sources = entries)
                                }
                            },
                            onFailure = { error ->
                                vm.snackbar.tryEmit(
                                    error.message ?: Graph.appContext.getString(R.string.generic_error),
                                )
                            },
                        )
                    }
                    else -> vm.openExternalIntent(incoming)
                }
            }
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

        Box(Modifier.fillMaxSize()) {
            // AppHost keeps external viewers reachable without broad storage permission while making
            // every full-screen page a real destination instead of layering it over MainScreen.
            AppHost(vm)
            pickerRequest?.let { request ->
                val leftState by vm.panes[0].state.collectAsStateWithLifecycle()
                val rightState by vm.panes[1].state.collectAsStateWithLifecycle()
                // Reading both states makes this overlay react immediately to checkbox changes.
                leftState.selection
                rightState.selection
                val selected = vm.panes
                    .flatMap { it.selectionEntries() }
                    .distinctBy { it.id }
                    .filter { entry ->
                        IntentUtils.canExternalRead(entry) &&
                            (request.allowedExtensions.isEmpty() ||
                                entry.name.substringAfterLast('.', "").lowercase() in request.allowedExtensions)
                    }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(12.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (request.allowedExtensions.isEmpty()) {
                                "ファイルを選択してください"
                            } else {
                                "${request.allowedExtensions.joinToString(" / ") { it.uppercase() }} を選択してください"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "チェックしたファイルを呼び出し元へ渡します。SMBの認証情報は共有しません。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { onReturnSelection(selected) },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Text("選択したファイルを使う (${selected.size})")
                        }
                    }
                }
            }

            outputRequest?.let { request ->
                val activePane by vm.activePane.collectAsStateWithLifecycle()
                val leftState by vm.panes[0].state.collectAsStateWithLifecycle()
                val rightState by vm.panes[1].state.collectAsStateWithLifecycle()
                val focusedDirId = if (activePane == 0) leftState.focusedDirId else rightState.focusedDirId
                var focusedDir by remember(request, focusedDirId) { mutableStateOf<XEntry?>(null) }
                LaunchedEffect(request, focusedDirId) {
                    focusedDir = withContext(Dispatchers.IO) {
                        focusedDirId?.let { id ->
                            runCatching { Graph.fsRegistry.forId(id).stat(id) }.getOrNull()
                        }
                    }
                }
                val canSave = focusedDir?.let { dir ->
                    dir.isDir && dir.scheme == XId.SCHEME_SMB &&
                        runCatching { Graph.fsRegistry.forEntry(dir).canWrite(dir) }.getOrDefault(false)
                } == true
                val location = focusedDir?.takeIf { it.scheme == XId.SCHEME_SMB }?.let { dir ->
                    runCatching { Graph.smbConnections.displayLabelPathForId(dir.id) }.getOrNull()
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(12.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("ClipForgeの保存先", style = MaterialTheme.typography.titleMedium)
                        Text(request.fileName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (canSave) {
                                "${location ?: "このSMBフォルダ"}へ、結合しながら直接書き込みます。"
                            } else {
                                "保存したいSMBフォルダを開いてください。ローカル保存は従来の共有経路を使います。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = { focusedDir?.let { onReturnOutput(it, request) } },
                            enabled = canSave,
                        ) {
                            Text("ここへ直接保存")
                        }
                    }
                }
            }
        }
        EditionStartupUpdateCheck()
    }
}

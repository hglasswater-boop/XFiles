package app.local1st.files.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.BrowserDisplayConfig
import app.local1st.files.core.prefs.BrowserDisplaySettings
import app.local1st.files.core.search.SearchHit
import app.local1st.files.core.thumb.PrivFile
import app.local1st.files.core.thumb.RemoteFile
import app.local1st.files.core.thumb.RemoteVideoThumb
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.EntryIcon
import app.local1st.files.ui.browser.EntryIcons
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.main.MainViewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn

private const val DEBOUNCE_MS = 400L
private const val MIN_QUERY_LENGTH = 2

private enum class SearchPhase { IDLE, SEARCHING, DONE }

/** Full-screen recursive filename search destination. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)
@Composable
fun SearchScreen(
    vm: MainViewModel,
    root: XEntry,
    onBack: () -> Unit,
) {
    val r = root
    val close = onBack
    val searchFailed = stringResource(R.string.search_failed)
    val display by BrowserDisplaySettings.state(Graph.appContext).collectAsState()

    // Navigation 3 retains saveable entry state while another destination is on top.
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember { mutableStateListOf<SearchHit>() }
    var phase by remember { mutableStateOf(SearchPhase.IDLE) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(r.id) {
        snapshotFlow { query.trim() }
            .debounce(DEBOUNCE_MS)
            .collectLatest { q ->
                results.clear()
                error = null
                if (q.length < MIN_QUERY_LENGTH) {
                    phase = SearchPhase.IDLE
                    return@collectLatest
                }
                phase = SearchPhase.SEARCHING
                try {
                    Graph.searchEngine.search(r, q)
                        .flowOn(Dispatchers.IO)
                        .collect { results.add(it) }
                    phase = SearchPhase.DONE
                } catch (e: IOException) {
                    error = e.message ?: searchFailed
                    phase = SearchPhase.DONE
                }
            }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = { Text(stringResource(R.string.search_files_hint)) },
                leadingIcon = {
                    TooltipIconButton(
                        stringResource(R.string.close_search),
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        onClick = close,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TooltipIconButton(
                            stringResource(R.string.clear_query),
                            Icons.Outlined.Close,
                            onClick = { query = "" },
                        )
                    }
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )

            Text(
                stringResource(R.string.searching_in, r.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            when (phase) {
                SearchPhase.SEARCHING -> Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LoadingIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (results.isEmpty()) stringResource(R.string.searching)
                        else stringResource(R.string.found_so_far, results.size),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                SearchPhase.DONE -> Text(
                    when {
                        error != null -> error.orEmpty()
                        results.size == 1 -> stringResource(R.string.one_result)
                        else -> stringResource(R.string.results, results.size)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SearchPhase.IDLE -> {}
            }

            if (results.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when (phase) {
                        SearchPhase.IDLE -> Text(
                            stringResource(R.string.search_minimum_length, MIN_QUERY_LENGTH),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SearchPhase.SEARCHING -> LoadingIndicator()
                        SearchPhase.DONE -> if (error == null) {
                            Text(
                                stringResource(R.string.no_results_for, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(results, key = { it.entry.id }) { hit ->
                        SearchHitRow(
                            hit = hit,
                            display = display,
                            onClick = { vm.revealSearchHit(hit.entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    hit: SearchHit,
    display: BrowserDisplayConfig,
    onClick: () -> Unit,
) {
    val entry = hit.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (EntryIcons.wantsThumbnail(entry)) {
            SearchThumbnail(entry, display)
        } else {
            EntryIcon(
                entry,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
            )

            val size = Format.bytes(entry.size)
            val date = Format.dateTime(entry.mtime)
            val metadata = listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Text(
                    metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                displayParentPath(hit.parentId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Uses the same local/privileged/SMB thumbnail models as the normal browser rows. */
@Composable
private fun SearchThumbnail(entry: XEntry, display: BrowserDisplayConfig) {
    val isVideo = FileTypes.categoryOf(entry.name, entry.mime) == FileCategory.VIDEO
    var loaded by remember(entry.id, entry.mtime, entry.size) { mutableStateOf(false) }
    val width = display.thumbnailSize.widthDp.dp
    val height = display.thumbnailSize.heightDp.dp

    Box(
        modifier = Modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            EntryIcon(
                entry,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
        AsyncImage(
            model = when {
                entry.scheme == XId.SCHEME_SMB && isVideo -> RemoteVideoThumb(entry)
                entry.scheme == XId.SCHEME_SMB -> RemoteFile(entry)
                isVideo -> VideoThumb(
                    path = entry.localPath ?: entry.path,
                    mtime = entry.mtime,
                    size = entry.size,
                    privileged = entry.localPath == null,
                )
                entry.localPath != null -> File(entry.localPath)
                else -> PrivFile(entry.path, entry.mtime, entry.size)
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { loaded = it is AsyncImagePainter.State.Success },
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

private fun displayParentPath(parentId: String): String {
    if (XId.schemeOf(parentId) != XId.SCHEME_SMB) {
        return parentId.substringAfter("://")
    }
    val raw = parentId.removePrefix("${XId.SCHEME_SMB}://").trim('/')
    if (raw.isEmpty()) return "SMB"
    val connectionId = raw.substringBefore('/')
    val relativePath = raw.substringAfter('/', "")
    val connection = Graph.smbConnections.find(connectionId)
        ?: return relativePath.ifBlank { "SMB" }
    return if (relativePath.isBlank()) {
        connection.name
    } else {
        "${connection.name} / $relativePath"
    }
}

package app.local1st.files.ui.browser

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.local1st.files.R
import app.local1st.files.core.fs.SmbTreeFileSystem
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.BrowserDisplaySettings
import app.local1st.files.core.prefs.SearchHistorySettings
import app.local1st.files.core.search.SearchHit
import app.local1st.files.di.Graph
import app.local1st.files.ui.dialogs.AddSmbConnectionDialog
import java.io.IOException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first

/**
 * Shared height of a breadcrumb pill and the compact screen's other-pane target chip,
 * so a single header row can keep them on one mid-line.
 */
val CrumbBarHeight = 40.dp
private val SearchHeaderHeight = 64.dp
private const val SEARCH_DEBOUNCE_MS = 400L
private const val SEARCH_MIN_QUERY_LENGTH = 2

private enum class PaneSearchPhase { IDLE, SEARCHING, DONE }

/**
 * One browser pane: breadcrumb/search bar + flattened tree list.
 * Search keeps the normal row selection model and operation toolbar while recursively walking
 * below the focused directory, so hits remain actionable instead of becoming a separate screen.
 */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
    FlowPreview::class,
)
@Composable
fun PaneView(
    controller: PaneController,
    active: Boolean,
    onActivate: () -> Unit,
    onOpenEntry: (XEntry) -> Unit,
    onEntryMenu: (XEntry) -> Unit,
    onInitialLayoutReady: (treeVersion: Long) -> Unit,
    breadcrumbAlignment: Alignment = Alignment.TopStart,
    headerStartPadding: Dp = 6.dp,
    headerEndPadding: Dp = 6.dp,
    headerOverlay: (@Composable () -> Unit)? = null,
    searchActive: Boolean = false,
    onSearchClose: () -> Unit = {},
    onSearchResultsChanged: (List<XEntry>) -> Unit = {},
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val display by BrowserDisplaySettings.state(Graph.appContext).collectAsStateWithLifecycle()
    val initialScrollIndex = state.initialScrollIndex
    var breadcrumbSortTarget by remember(controller) {
        mutableStateOf<Pair<String, String>?>(null)
    }
    var searchQuery by rememberSaveable(controller) { mutableStateOf("") }
    var searchRoot by remember(controller) { mutableStateOf<XEntry?>(null) }
    val searchResults = remember(controller) { mutableStateListOf<SearchHit>() }
    var searchPhase by remember(controller) { mutableStateOf(PaneSearchPhase.IDLE) }
    var searchError by remember(controller) { mutableStateOf<String?>(null) }
    val currentOnSearchResultsChanged by rememberUpdatedState(onSearchResultsChanged)

    val query = searchQuery.trim()
    val showingSearchResults = searchActive && query.length >= SEARCH_MIN_QUERY_LENGTH
    val displayNodes = if (showingSearchResults) {
        searchResults.mapIndexed { index, hit ->
            TreeNode(
                entry = hit.entry,
                key = "search|${hit.parentId}|${hit.entry.id}",
                depth = 0,
                expanded = false,
                loading = false,
                guides = emptyList(),
                isLastChild = index == searchResults.lastIndex,
            )
        }
    } else {
        state.nodes
    }
    val searchParents = if (showingSearchResults) {
        searchResults.associate { it.entry.id to it.parentId }
    } else {
        emptyMap()
    }
    val searchSelectionTargetMap = if (
        showingSearchResults && searchPhase == PaneSearchPhase.DONE
    ) {
        searchSelectionTargets(searchResults.map { it.entry })
    } else {
        emptyMap()
    }
    val allSearchTargets = searchSelectionTargetMap.values.flatten().distinctBy { it.id }
    val selectedSearchTargetCount = allSearchTargets.count { it.id in state.selection }
    val searchSelectAllState = when {
        allSearchTargets.isEmpty() || selectedSearchTargetCount == 0 -> ToggleableState.Off
        selectedSearchTargetCount == allSearchTargets.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    fun setSearchTargetsSelected(targets: List<XEntry>, selected: Boolean) {
        val selectedIds = state.selection
        targets.forEach { target ->
            if ((target.id in selectedIds) != selected) controller.toggleSelect(target)
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchRoot = controller.focusedDirEntry()
            searchQuery = ""
            searchResults.clear()
            searchError = null
            searchPhase = PaneSearchPhase.IDLE
        } else {
            searchRoot = null
            searchQuery = ""
            searchResults.clear()
            searchError = null
            searchPhase = PaneSearchPhase.IDLE
            currentOnSearchResultsChanged(emptyList())
        }
    }

    LaunchedEffect(searchActive, searchRoot?.id) {
        if (!searchActive) return@LaunchedEffect
        snapshotFlow { searchQuery.trim() }
            .debounce(SEARCH_DEBOUNCE_MS)
            .collectLatest { currentQuery ->
                searchResults.clear()
                searchError = null
                if (currentQuery.length < SEARCH_MIN_QUERY_LENGTH) {
                    searchPhase = PaneSearchPhase.IDLE
                    return@collectLatest
                }
                val root = searchRoot
                if (root == null) {
                    searchPhase = PaneSearchPhase.DONE
                    return@collectLatest
                }
                searchPhase = PaneSearchPhase.SEARCHING
                try {
                    Graph.searchEngine.search(root, currentQuery).collect { hit ->
                        searchResults.add(hit)
                    }
                    SearchHistorySettings.add(Graph.appContext, currentQuery)
                    searchPhase = PaneSearchPhase.DONE
                } catch (error: IOException) {
                    searchError = error.message ?: Graph.appContext.getString(R.string.search_failed)
                    searchPhase = PaneSearchPhase.DONE
                }
            }
    }

    LaunchedEffect(searchActive) {
        if (!searchActive) return@LaunchedEffect
        snapshotFlow { searchResults.map { it.entry } }.collectLatest { entries ->
            currentOnSearchResultsChanged(entries)
        }
    }

    // Keep recursive search results in step with destructive/move operations. The main browser
    // already consumes the same event to refresh its tree; this removes hits that may live below
    // currently collapsed ancestors and therefore would not otherwise be represented in nodes.
    LaunchedEffect(searchActive) {
        if (!searchActive) return@LaunchedEffect
        Graph.opEngine.events.collect { event ->
            val removed = event.removedEntryIds
            if (removed.isNotEmpty()) {
                searchResults.removeAll { hit -> searchEntryWasRemoved(hit.entry.id, removed) }
            }
        }
    }

    fun updateSearchQuery(value: String) {
        if (value == searchQuery) return
        controller.clearSelection()
        searchQuery = value
        searchResults.clear()
        searchError = null
        searchPhase = if (value.trim().length >= SEARCH_MIN_QUERY_LENGTH) {
            PaneSearchPhase.SEARCHING
        } else {
            PaneSearchPhase.IDLE
        }
    }

    if (initialScrollIndex == null) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = if (active) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
                if (searchActive) {
                    PaneSearchHeader(
                        query = searchQuery,
                        phase = searchPhase,
                        onQueryChange = ::updateSearchQuery,
                        onClose = onSearchClose,
                    )
                } else {
                    PaneHeader(
                        focusedDirId = state.focusedDirId,
                        active = active,
                        breadcrumbAlignment = breadcrumbAlignment,
                        headerStartPadding = headerStartPadding,
                        headerEndPadding = headerEndPadding,
                        headerOverlay = headerOverlay,
                        onCrumbClick = { id ->
                            onActivate()
                            controller.revealPath(id)
                        },
                        onCrumbLongClick = { id, name ->
                            onActivate()
                            breadcrumbSortTarget = id to name
                        },
                    )
                }
            }
        }
        breadcrumbSortTarget?.let { (id, name) ->
            BreadcrumbSortDialog(
                folderId = id,
                folderName = name,
                onDismiss = { breadcrumbSortTarget = null },
            )
        }
        return
    }

    // Anchor the visible indentation window to the currently focused directory and its direct
    // children. With a two-level setting, for example, the current folder remains one level in
    // and its children two levels in; older ancestors collapse to the left instead of all deeper
    // rows being flattened at the same absolute depth.
    val focusedDepth = state.nodes.firstOrNull { it.entry.id == state.focusedDirId }?.depth ?: 0
    val depthBase = (focusedDepth + 1 - display.treeLevels).coerceAtLeast(0)

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    val currentOnInitialLayoutReady by rememberUpdatedState(onInitialLayoutReady)
    var richRowsEnabled by rememberSaveable(controller) { mutableStateOf(false) }
    var itemAnimationsEnabled by rememberSaveable(controller) { mutableStateOf(false) }
    var showAddSmbServer by rememberSaveable(controller) { mutableStateOf(false) }
    var preSearchIndex by remember(controller) { mutableStateOf<Int?>(null) }

    LaunchedEffect(controller, listState, state.treeVersion) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        currentOnInitialLayoutReady(state.treeVersion)
        if (!richRowsEnabled) {
            withFrameNanos { }
            withFrameNanos { }
            richRowsEnabled = true
        }
    }

    LaunchedEffect(state.startupSettled, richRowsEnabled) {
        itemAnimationsEnabled = false
        if (state.startupSettled && richRowsEnabled) {
            withFrameNanos { }
            itemAnimationsEnabled = true
        }
    }

    LaunchedEffect(controller, listState) {
        controller.scrollTo.collectLatest { request ->
            // Keep this request until the browser is visible again and the expanded path has
            // propagated through the flattened tree.
            val ready = controller.state.first { paneState ->
                paneState.nodes.any { it.entry.id == request.id }
            }
            val index = ready.nodes.indexOfFirst { it.entry.id == request.id }
            if (index < 0) return@collectLatest
            if (request.animate) listState.animateScrollToItem(index) else listState.scrollToItem(index)
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            preSearchIndex = listState.firstVisibleItemIndex
        } else {
            val restoreIndex = preSearchIndex
            preSearchIndex = null
            if (restoreIndex != null && state.nodes.isNotEmpty()) {
                listState.scrollToItem(restoreIndex.coerceAtMost(state.nodes.lastIndex))
            }
        }
    }

    LaunchedEffect(searchActive, searchQuery) {
        if (showingSearchResults && displayNodes.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (active) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            val statusPad = WindowInsets.statusBarsIgnoringVisibility
                .asPaddingValues().calculateTopPadding()
            val headerHeight = if (searchActive) SearchHeaderHeight else CrumbBarHeight
            val listTopInset = statusPad + 8.dp + headerHeight

            when {
                state.loadingRoots && state.nodes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
                showingSearchResults && searchPhase == PaneSearchPhase.SEARCHING &&
                    searchResults.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = listTopInset,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
                showingSearchResults && searchPhase == PaneSearchPhase.DONE &&
                    searchResults.isEmpty() -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = listTopInset,
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            searchError ?: stringResource(R.string.no_results_for, query),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (searchError == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            top = if (searchActive) 0.dp else listTopInset,
                            bottom = contentPadding.calculateBottomPadding(),
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (searchActive) Modifier.padding(top = listTopInset)
                                else Modifier,
                            ),
                    ) {
                        items(
                            count = displayNodes.size,
                            key = { displayNodes[it].key },
                        ) { index ->
                            val rawNode = displayNodes[index]
                            val visualDepth = if (showingSearchResults) {
                                0
                            } else {
                                (rawNode.depth - depthBase).coerceIn(0, display.treeLevels)
                            }
                            val guideBase = (rawNode.depth - visualDepth).coerceAtLeast(0)
                            val visualGuides = if (visualDepth == rawNode.depth && guideBase == 0) {
                                rawNode.guides
                            } else {
                                List(visualDepth + 1) { localDepth ->
                                    rawNode.guides.getOrNull(guideBase + localDepth) ?: false
                                }
                            }
                            val node = if (
                                visualDepth == rawNode.depth && visualGuides === rawNode.guides
                            ) {
                                rawNode
                            } else {
                                rawNode.copy(depth = visualDepth, guides = visualGuides)
                            }
                            val addSmbServer = node.entry.id == SmbTreeFileSystem.ADD_SERVER_ID
                            val searchSelectionTargetsForRow = if (showingSearchResults) {
                                searchSelectionTargetMap[node.entry.id].orEmpty()
                            } else {
                                emptyList()
                            }
                            val searchRowSelected = showingSearchResults &&
                                searchSelectionTargetsForRow.isNotEmpty() &&
                                searchSelectionTargetsForRow.all { it.id in state.selection }
                            Column {
                                EntryRow(
                                    node = node,
                                    selected = if (showingSearchResults) {
                                        searchRowSelected
                                    } else {
                                        node.entry.id in state.selection
                                    },
                                    focused = !showingSearchResults &&
                                        node.entry.id == state.focusedDirId,
                                    onClick = {
                                        onActivate()
                                        when {
                                            addSmbServer -> showAddSmbServer = true
                                            showingSearchResults && node.entry.isContainer -> {
                                                controller.revealPath(node.entry.id)
                                                onSearchClose()
                                            }
                                            else -> onOpenEntry(node.entry)
                                        }
                                    },
                                    onLongClick = {
                                        onActivate()
                                        if (addSmbServer) showAddSmbServer = true
                                        else onEntryMenu(node.entry)
                                    },
                                    onToggleSelect = {
                                        onActivate()
                                        if (showingSearchResults && searchSelectionTargetsForRow.isNotEmpty()) {
                                            setSearchTargetsSelected(
                                                searchSelectionTargetsForRow,
                                                !searchRowSelected,
                                            )
                                        } else {
                                            controller.toggleSelect(node.entry)
                                        }
                                    },
                                    enabled = !state.snapshotOnly,
                                    richContent = richRowsEnabled,
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .then(
                                            if (itemAnimationsEnabled) Modifier.animateItem()
                                            else Modifier,
                                        ),
                                )
                                if (showingSearchResults) {
                                    val parentId = searchParents[node.entry.id]
                                    if (parentId != null) {
                                        Text(
                                            displaySearchParentPath(parentId),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(
                                                start = 56.dp,
                                                end = 12.dp,
                                                bottom = 4.dp,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (searchActive) {
                PaneSearchHeader(
                    query = searchQuery,
                    phase = searchPhase,
                    selectAllState = searchSelectAllState.takeIf { allSearchTargets.isNotEmpty() },
                    onSelectAll = {
                        onActivate()
                        setSearchTargetsSelected(
                            allSearchTargets,
                            searchSelectAllState != ToggleableState.On,
                        )
                    },
                    onQueryChange = ::updateSearchQuery,
                    onClose = onSearchClose,
                )
            } else {
                PaneHeader(
                    focusedDirId = state.focusedDirId,
                    active = active,
                    breadcrumbAlignment = breadcrumbAlignment,
                    headerStartPadding = headerStartPadding,
                    headerEndPadding = headerEndPadding,
                    headerOverlay = headerOverlay,
                    onCrumbClick = { id ->
                        onActivate()
                        controller.revealPath(id)
                    },
                    onCrumbLongClick = { id, name ->
                        onActivate()
                        breadcrumbSortTarget = id to name
                    },
                )
            }
        }
    }

    if (showAddSmbServer) {
        AddSmbConnectionDialog(onDismiss = { showAddSmbServer = false })
    }
    breadcrumbSortTarget?.let { (id, name) ->
        BreadcrumbSortDialog(
            folderId = id,
            folderName = name,
            onDismiss = { breadcrumbSortTarget = null },
        )
    }
}

@Composable
private fun BoxScope.PaneSearchHeader(
    query: String,
    phase: PaneSearchPhase,
    selectAllState: ToggleableState? = null,
    onSelectAll: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search_files_hint)) },
        leadingIcon = {
            IconButton(
                onClick = {
                    keyboard?.hide()
                    onClose()
                },
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.close_search),
                )
            }
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (phase == PaneSearchPhase.SEARCHING && query.trim().length >= SEARCH_MIN_QUERY_LENGTH) {
                    LoadingIndicator(Modifier.size(20.dp))
                }
                if (selectAllState != null) {
                    TriStateCheckbox(state = selectAllState, onClick = onSelectAll)
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.clear_query),
                        )
                    }
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.PaneHeader(
    focusedDirId: String?,
    active: Boolean,
    breadcrumbAlignment: Alignment,
    headerStartPadding: Dp,
    headerEndPadding: Dp,
    headerOverlay: (@Composable () -> Unit)?,
    onCrumbClick: (String) -> Unit,
    onCrumbLongClick: (String, String) -> Unit,
) {
    val chipOnStart = headerOverlay != null && breadcrumbAlignment == Alignment.TopEnd
    val chipOnEnd = headerOverlay != null && !chipOnStart
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(
                start = headerStartPadding,
                top = 4.dp,
                end = headerEndPadding,
            ),
    ) {
        if (chipOnStart) {
            headerOverlay?.invoke()
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (chipOnStart) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            BreadcrumbBar(
                focusedDirId = focusedDirId,
                active = active,
                onCrumbClick = onCrumbClick,
                onCrumbLongClick = onCrumbLongClick,
                modifier = Modifier.wrapContentWidth(
                    align = if (chipOnStart) Alignment.End else Alignment.Start,
                    unbounded = false,
                ),
            )
        }
        if (chipOnEnd) {
            Spacer(Modifier.width(8.dp))
            headerOverlay?.invoke()
        }
    }
}

@Composable
private fun BreadcrumbBar(
    focusedDirId: String?,
    active: Boolean,
    onCrumbClick: (String) -> Unit,
    onCrumbLongClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val crumbs = crumbsFor(focusedDirId)
    Surface(
        shape = RoundedCornerShape(CrumbBarHeight / 2),
        color = if (active) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
        },
        modifier = modifier.defaultMinSize(
            minWidth = CrumbBarHeight,
            minHeight = CrumbBarHeight,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .horizontalScroll(rememberScrollState(), reverseScrolling = true)
                .padding(horizontal = 12.dp),
        ) {
            if (crumbs.isEmpty()) {
                Text(
                    "XFiles",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }
            crumbs.forEachIndexed { index, (id, name) ->
                if (index > 0) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        index == crumbs.lastIndex && active -> MaterialTheme.colorScheme.primary
                        index == crumbs.lastIndex -> MaterialTheme.colorScheme.onSecondaryContainer
                        !active -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onCrumbClick(id) },
                            onLongClick = { onCrumbLongClick(id, name) },
                        )
                        .padding(vertical = 4.dp, horizontal = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun crumbsFor(focusedDirId: String?): List<Pair<String, String>> {
    focusedDirId ?: return emptyList()
    val chain = generateSequence(focusedDirId) { XId.parent(it) }.toList().reversed()
    return chain.map { id ->
        val raw = id.substringAfter("://")
        val name = when {
            id == "${XId.SCHEME_SMB}://" -> "SMB"
            id.startsWith("${XId.SCHEME_SMB}://") && raw.isNotBlank() && !raw.contains('/') ->
                Graph.smbConnections.displayLabelPathForId(id)
            raw == "@user" -> stringResource(R.string.installed_apps)
            raw == "@system" -> stringResource(R.string.system_apps)
            else -> raw.trimEnd('/').substringAfterLast('/')
                .substringAfterLast(XId.ARCHIVE_SEP)
                .ifEmpty { if (id.startsWith(XId.SCHEME_APPS)) stringResource(R.string.apps) else "/" }
        }
        id to name
    }
}

private fun displaySearchParentPath(parentId: String): String =
    if (XId.schemeOf(parentId) == XId.SCHEME_SMB) {
        Graph.smbConnections.displayLabelPathForId(parentId)
    } else {
        parentId.substringAfter("://")
    }

internal fun searchSelectionTargets(entries: List<XEntry>): Map<String, List<XEntry>> {
    if (entries.isEmpty()) return emptyMap()
    val unique = LinkedHashMap<String, XEntry>()
    entries.forEach { unique[it.id] = it }
    val matchedIds = unique.keys
    val matchedAncestors = HashSet<String>()
    unique.values.forEach { entry ->
        var current = XId.parent(entry.id)
        while (current != null) {
            if (current in matchedIds) matchedAncestors += current
            current = XId.parent(current)
        }
    }
    val targets = LinkedHashMap<String, MutableList<XEntry>>()
    unique.values.filterNot { it.id in matchedAncestors }.forEach { terminal ->
        var current: String? = terminal.id
        while (current != null) {
            if (current in matchedIds) targets.getOrPut(current) { mutableListOf() }.add(terminal)
            current = XId.parent(current)
        }
    }
    return unique.keys.associateWith { targets[it].orEmpty() }
}

private fun searchEntryWasRemoved(entryId: String, removedIds: Set<String>): Boolean {
    var current: String? = entryId
    while (current != null) {
        if (current in removedIds) return true
        current = XId.parent(current)
    }
    return false
}

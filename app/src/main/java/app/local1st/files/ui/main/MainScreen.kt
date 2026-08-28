package app.local1st.files.ui.main

import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.local1st.files.BuildConfig
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.di.Graph
import app.local1st.files.ui.browser.CrumbBarHeight
import app.local1st.files.ui.browser.PaneView
import app.local1st.files.ui.components.TooltipIconButton
import app.local1st.files.ui.dialogs.DialogRequest

private val CompactTargetChipMaxWidth = 96.dp
private val CompactIosChevronSize = 12.dp
private const val TvExitBackWindowMs = 2_000L

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val sessionReady by vm.sessionReady.collectAsStateWithLifecycle()
    val activePane by vm.activePane.collectAsStateWithLifecycle()
    val pendingTransfer by vm.pendingTransfer.collectAsStateWithLifecycle()
    val activeState by vm.panes[activePane].state.collectAsStateWithLifecycle()
    val otherPaneController = vm.panes[1 - activePane]
    val otherPaneState by otherPaneController.state.collectAsStateWithLifecycle()
    val otherPaneDestination = otherPaneController.focusedDirEntry()
    val otherPaneName = paneLocationName(otherPaneDestination, otherPaneState.focusedDirId)
    val otherPanePath = paneLocationPath(otherPaneDestination, otherPaneState.focusedDirId)
    val canUseOtherPane = isFileOperationDestination(otherPaneDestination)
    val transferDestination = vm.activeCtrl.focusedDirEntry()
    val transferDestinationName = paneLocationName(transferDestination, activeState.focusedDirId)
    val canConfirmTransfer = isFileOperationDestination(transferDestination)
    var initiallyLaidOutPanes by remember(vm) { mutableStateOf<Set<Int>>(emptySet()) }
    var startupContentReady by rememberSaveable(vm) {
        mutableStateOf(sessionReady && activeState.snapshotOnly)
    }
    var searchPane by rememberSaveable(vm) { mutableStateOf<Int?>(null) }
    var searchEntries by remember(vm) { mutableStateOf<Map<String, XEntry>>(emptyMap()) }
    var lastTvRootBackAt by remember { mutableStateOf(0L) }
    val isTvEdition = BuildConfig.APPLICATION_ID.endsWith(".tv")
    val wideLayout = !isTvEdition && LocalConfiguration.current.screenWidthDp >= 700
    val context = LocalContext.current
    val editionSideRailOpen = isTvEdition && isEditionSideRailOpen()

    fun closeSearch(paneIndex: Int) {
        vm.panes[paneIndex].clearSelection()
        if (searchPane == paneIndex) {
            searchPane = null
            searchEntries = emptyMap()
        }
    }

    val selectionCount = activeState.selection.size
    val selectedEntries = if (selectionCount > 0) {
        val browserEntries = vm.activeCtrl.selectionEntries().associateBy { it.id }
        activeState.selection.mapNotNull { id ->
            if (searchPane == activePane) searchEntries[id] ?: browserEntries[id]
            else browserEntries[id]
        }
    } else {
        emptyList()
    }
    val selectedFiles = selectedEntries.filter { !it.isDir }
    val canShareSelection = selectedFiles.isNotEmpty() &&
        selectedFiles.all { app.local1st.files.core.util.IntentUtils.canExternalRead(it) }
    val unavailableDestinationLabel = stringResource(R.string.cannot_write, otherPaneName)
    val copyTargetLabel = stringResource(R.string.copy_to)
    val moveTargetLabel = stringResource(R.string.move_to)
    val compressTargetLabel = if (canUseOtherPane) {
        "${stringResource(R.string.compress_to)} $otherPaneName"
    } else {
        unavailableDestinationLabel
    }

    BackHandler(enabled = pendingTransfer != null) {
        vm.cancelTransferDestination()
    }
    BackHandler(enabled = pendingTransfer == null && searchPane == activePane) {
        closeSearch(activePane)
    }
    BackHandler(
        enabled = pendingTransfer == null && searchPane != activePane && selectionCount > 0,
    ) {
        vm.activeCtrl.clearSelection()
    }
    BackHandler(
        enabled = isTvEdition &&
            pendingTransfer == null &&
            searchPane != activePane &&
            selectionCount == 0,
    ) {
        val controller = vm.activeCtrl
        val paneState = controller.state.value
        val focusedId = paneState.focusedDirId
        val focusedIndex = focusedId?.let { id ->
            paneState.nodes.indexOfLast { node -> node.entry.id == id }
        } ?: -1
        val focusedNode = paneState.nodes.getOrNull(focusedIndex)
        val parentNode = if (focusedNode != null && focusedNode.depth > 0) {
            paneState.nodes
                .take(focusedIndex)
                .lastOrNull { node -> node.depth == focusedNode.depth - 1 }
        } else {
            null
        }

        if (focusedNode != null && parentNode != null) {
            if (focusedNode.entry.isContainer) controller.collapse(focusedNode.entry)
            controller.revealPath(parentNode.entry.id, animate = false)
            lastTvRootBackAt = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTvRootBackAt <= TvExitBackWindowMs) {
                (context as? Activity)?.finish()
            } else {
                lastTvRootBackAt = now
                vm.snackbar.tryEmit(context.getString(R.string.press_back_again_to_exit))
            }
        }
    }

    // TV Back closes an open side rail before any browser/root Back behavior can run.
    BackHandler(enabled = editionSideRailOpen) {
        dismissEditionSideRail()
        lastTvRootBackAt = 0L
    }

    // No top app bar at all: the panes extend under the status bar, and the few former
    // top-bar actions live elsewhere (search in the bottom toolbar, Settings in More).
    // On phones the freed top-end slot exposes the otherwise hidden destination pane.
    // The explicit background paints the pane gutters and rounded-corner gaps that
    // Scaffold used to cover.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val listPadding = PaddingValues(bottom = if (isTvEdition) 24.dp else 120.dp)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = !isTvEdition && maxWidth >= 700.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    vm.panes.forEachIndexed { index, pane ->
                        PaneView(
                            controller = pane,
                            active = activePane == index,
                            onActivate = { vm.setActivePane(index) },
                            onOpenEntry = { entry ->
                                if (pendingTransfer != null && entry.isContainer) {
                                    pane.toggleExpand(entry)
                                } else if (pendingTransfer == null) {
                                    vm.openEntry(pane, entry)
                                }
                            },
                            onEntryMenu = { entry ->
                                if (pendingTransfer == null) {
                                    vm.dialog.value = DialogRequest.EntryMenu(entry)
                                }
                            },
                            onInitialLayoutReady = { version ->
                                initiallyLaidOutPanes = initiallyLaidOutPanes + index
                                vm.onPaneInitialLayoutReady(index, version)
                            },
                            searchActive = searchPane == index,
                            onSearchClose = { closeSearch(index) },
                            onSearchResultsChanged = { entries ->
                                if (searchPane == index) {
                                    searchEntries = entries.associateBy { it.id }
                                }
                            },
                            contentPadding = listPadding,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            } else {
                val pagerState = rememberPagerState(initialPage = activePane) { 2 }
                LaunchedEffect(pagerState, sessionReady) {
                    if (!sessionReady) return@LaunchedEffect
                    snapshotFlow { pagerState.currentPage }.collect { vm.setActivePane(it) }
                }
                LaunchedEffect(activePane) {
                    if (pagerState.currentPage != activePane &&
                        !pagerState.isScrollInProgress
                    ) {
                        if (sessionReady) pagerState.animateScrollToPage(activePane)
                        else pagerState.scrollToPage(activePane)
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    // TV intentionally uses this single-page path even on wide displays. The
                    // inactive pane remains available as an operation target without paying the
                    // cost of composing both pane trees at once.
                    beyondViewportPageCount = 0,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val pane = vm.panes[page]
                    PaneView(
                        controller = pane,
                        active = activePane == page,
                        onActivate = { vm.setActivePane(page) },
                        onOpenEntry = { entry ->
                            if (pendingTransfer != null && entry.isContainer) {
                                pane.toggleExpand(entry)
                            } else if (pendingTransfer == null) {
                                vm.openEntry(pane, entry)
                            }
                        },
                        onEntryMenu = { entry ->
                            if (pendingTransfer == null) {
                                vm.dialog.value = DialogRequest.EntryMenu(entry)
                            }
                        },
                        onInitialLayoutReady = { version ->
                            initiallyLaidOutPanes = initiallyLaidOutPanes + page
                            vm.onPaneInitialLayoutReady(page, version)
                            if (page == activePane) startupContentReady = true
                        },
                        breadcrumbAlignment = if (page == 0) {
                            Alignment.TopStart
                        } else {
                            Alignment.TopEnd
                        },
                        headerOverlay = {
                            val targetPane = 1 - page
                            val targetController = vm.panes[targetPane]
                            val targetState = if (targetPane == activePane) {
                                activeState
                            } else {
                                otherPaneState
                            }
                            val targetDestination = targetController.focusedDirEntry()
                            OtherPaneTargetChip(
                                name = paneLocationName(
                                    targetDestination,
                                    targetState.focusedDirId,
                                ),
                                path = paneLocationPath(
                                    targetDestination,
                                    targetState.focusedDirId,
                                ),
                                ready = targetDestination != null,
                                writable = isFileOperationDestination(targetDestination),
                                activePane = page,
                                onClick = { vm.setActivePane(targetPane) },
                            )
                        },
                        searchActive = searchPane == page,
                        onSearchClose = { closeSearch(page) },
                        onSearchResultsChanged = { entries ->
                            if (searchPane == page) {
                                searchEntries = entries.associateBy { it.id }
                            }
                        },
                        contentPadding = listPadding,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .then(
                                if (isTvEdition && searchPane != page) {
                                    Modifier.onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            false
                                        } else {
                                            when (event.key) {
                                                Key.DirectionLeft -> {
                                                    requestEditionSideRail(left = true)
                                                    true
                                                }
                                                Key.DirectionRight -> {
                                                    requestEditionSideRail(left = false)
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }

        // The list scrolls under the transparent status bar; this gradient keeps the
        // clock and icons readable over whatever content passes beneath.
        // All insets on this screen use the IgnoringVisibility variants: the video
        // player hides the system bars for its own window, and the plain insets would
        // collapse to 0 and reflow this whole page under it — every trip through a
        // video would visibly shift the browser.
        val statusPad = WindowInsets.statusBarsIgnoringVisibility
            .asPaddingValues().calculateTopPadding()
        Box(
            Modifier
                .fillMaxWidth()
                .height(statusPad + 16.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Phones keep the bottom toolbar. TV exposes equivalent actions as edge focus targets so
        // users can reach commands from any row without first travelling back to the list top.
        if (!isTvEdition && pendingTransfer == null) {
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .offset(y = (-24).dp),
                content = {
                    AnimatedContent(
                        targetState = selectionCount > 0,
                        label = "toolbar",
                    ) { hasSelection ->
                        Row {
                            if (hasSelection) {
                                TooltipIconButton(stringResource(R.string.clear), Icons.Outlined.Close) {
                                    vm.activeCtrl.clearSelection()
                                }
                                Text(
                                    "$selectionCount",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                )
                                TooltipIconButton(
                                    label = copyTargetLabel,
                                    icon = Icons.Outlined.ContentCopy,
                                    enabled = selectedEntries.isNotEmpty(),
                                ) {
                                    vm.chooseTransferDestination(
                                        move = false,
                                        sources = selectedEntries,
                                    )
                                }
                                TooltipIconButton(
                                    label = moveTargetLabel,
                                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                    enabled = selectedEntries.isNotEmpty(),
                                ) {
                                    vm.chooseTransferDestination(
                                        move = true,
                                        sources = selectedEntries,
                                    )
                                }
                                TooltipIconButton(
                                    stringResource(R.string.delete),
                                    Icons.Outlined.Delete,
                                    enabled = selectedEntries.isNotEmpty(),
                                ) { vm.requestDelete(selectedEntries) }
                                TooltipIconButton(
                                    label = compressTargetLabel,
                                    icon = Icons.Outlined.Archive,
                                    enabled = canUseOtherPane && selectedEntries.isNotEmpty(),
                                ) { vm.requestCompress(selectedEntries) }
                                TooltipIconButton(
                                    label = stringResource(
                                        if (canShareSelection) R.string.share
                                        else R.string.share_requires_local_files,
                                    ),
                                    icon = Icons.Outlined.Share,
                                    enabled = canShareSelection,
                                ) { vm.shareSelection(selectedEntries) }
                            } else {
                                TooltipIconButton(
                                    stringResource(R.string.new_folder),
                                    Icons.Outlined.CreateNewFolder,
                                ) {
                                    vm.requestNewFolder()
                                }
                                TooltipIconButton(
                                    stringResource(R.string.new_text_file),
                                    Icons.AutoMirrored.Outlined.NoteAdd,
                                ) {
                                    vm.requestNewTextFile()
                                }
                                TooltipIconButton(
                                    stringResource(R.string.search),
                                    Icons.Outlined.Search,
                                ) {
                                    if (searchPane == activePane) {
                                        closeSearch(activePane)
                                    } else {
                                        searchPane?.let { previous ->
                                            vm.panes[previous].clearSelection()
                                        }
                                        searchEntries = emptyMap()
                                        searchPane = activePane
                                    }
                                }
                                TooltipIconButton(
                                    stringResource(R.string.refresh),
                                    Icons.Outlined.Refresh,
                                ) {
                                    vm.activeCtrl.refreshAllExpanded()
                                }
                                TooltipIconButton(
                                    stringResource(R.string.more),
                                    Icons.Outlined.MoreVert,
                                ) {
                                    vm.dialog.value = DialogRequest.EntryMenu(
                                        entry = vm.activeCtrl.focusedDirEntry(),
                                        showSettings = true,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        } else if (!isTvEdition) {
            val transfer = pendingTransfer
            if (transfer != null) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .offset(y = (-24).dp),
                    content = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TooltipIconButton(
                                label = stringResource(R.string.cancel),
                                icon = Icons.Outlined.Close,
                            ) { vm.cancelTransferDestination() }
                            Text(
                                "${transfer.sources.size}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                transferDestinationName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .widthIn(max = 140.dp),
                            )
                            TooltipIconButton(
                                label = if (transfer.move) {
                                    "ここへ移動: $transferDestinationName"
                                } else {
                                    "ここへコピー: $transferDestinationName"
                                },
                                icon = if (transfer.move) {
                                    Icons.AutoMirrored.Outlined.DriveFileMove
                                } else {
                                    Icons.Outlined.ContentCopy
                                },
                                enabled = canConfirmTransfer,
                            ) { vm.confirmTransferCurrentDestination() }
                        }
                    },
                )
            }
        }

        if (isTvEdition) {
            EditionSideActionMenu(
                selectionCount = selectionCount,
                selectionAvailable = selectedEntries.isNotEmpty(),
                canUseOtherPane = canUseOtherPane,
                canShareSelection = canShareSelection,
                copyTargetLabel = copyTargetLabel,
                moveTargetLabel = moveTargetLabel,
                compressTargetLabel = compressTargetLabel,
                transferActive = pendingTransfer != null,
                transferMove = pendingTransfer?.move == true,
                transferSourceCount = pendingTransfer?.sources?.size ?: 0,
                transferDestinationName = transferDestinationName,
                canConfirmTransfer = canConfirmTransfer,
                onNewFolder = { vm.requestNewFolder() },
                onNewTextFile = { vm.requestNewTextFile() },
                onSearch = {
                    if (searchPane == activePane) {
                        closeSearch(activePane)
                    } else {
                        searchPane?.let { previous -> vm.panes[previous].clearSelection() }
                        searchEntries = emptyMap()
                        searchPane = activePane
                    }
                },
                onRefresh = { vm.activeCtrl.refreshAllExpanded() },
                onSettings = { vm.openSettings() },
                onMore = {
                    vm.dialog.value = DialogRequest.EntryMenu(
                        entry = vm.activeCtrl.focusedDirEntry(),
                        showSettings = true,
                    )
                },
                onClear = { vm.activeCtrl.clearSelection() },
                onCopy = { vm.chooseTransferDestination(move = false, sources = selectedEntries) },
                onMove = { vm.chooseTransferDestination(move = true, sources = selectedEntries) },
                onDelete = { vm.requestDelete(selectedEntries) },
                onCompress = { vm.requestCompress(selectedEntries) },
                onShare = { vm.shareSelection(selectedEntries) },
                onCancelTransfer = { vm.cancelTransferDestination() },
                onConfirmTransfer = { vm.confirmTransferCurrentDestination() },
            )
        }

        val requiredPanes = if (wideLayout) vm.panes.indices else listOf(activePane)
        val currentLayoutReady = requiredPanes.all { index ->
            index in initiallyLaidOutPanes
        }
        LaunchedEffect(sessionReady, currentLayoutReady) {
            if (sessionReady && currentLayoutReady) startupContentReady = true
        }
        if (!sessionReady || !startupContentReady) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherPaneTargetChip(
    name: String,
    path: String,
    ready: Boolean,
    writable: Boolean,
    activePane: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val switchLabel = "${stringResource(R.string.switch_pane)}: $path"
    val unavailableLabel = stringResource(R.string.cannot_write, name)
    val tooltip = if (ready && !writable) {
        "$switchLabel · $unavailableLabel"
    } else {
        switchLabel
    }
    val containerColor = when {
        !ready -> MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)
        writable -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
    }
    val contentColor = when {
        !ready -> MaterialTheme.colorScheme.onSurfaceVariant
        writable -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onErrorContainer
    }
    val pointingRight = activePane == 0
    val chevron = if (pointingRight) {
        Icons.AutoMirrored.Outlined.ArrowForwardIos
    } else {
        Icons.AutoMirrored.Outlined.ArrowBackIos
    }

    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                TooltipAnchorPosition.Below,
            ),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            CompositionLocalProvider(
                LocalMinimumInteractiveComponentSize provides CrumbBarHeight,
            ) {
                Surface(
                    onClick = onClick,
                    shape = MaterialTheme.shapes.extraLarge,
                    color = containerColor,
                    contentColor = contentColor,
                    modifier = Modifier
                        .widthIn(max = CompactTargetChipMaxWidth)
                        .semantics(mergeDescendants = true) {
                            contentDescription = switchLabel
                            if (ready && !writable) {
                                stateDescription = unavailableLabel
                            }
                        },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .defaultMinSize(minHeight = CrumbBarHeight)
                            .padding(
                                start = if (pointingRight) 10.dp else 6.dp,
                                top = 4.dp,
                                end = if (pointingRight) 6.dp else 10.dp,
                                bottom = 4.dp,
                            ),
                    ) {
                        if (!pointingRight) {
                            Icon(
                                chevron,
                                contentDescription = null,
                                modifier = Modifier.size(CompactIosChevronSize),
                            )
                        }
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .weight(1f, fill = false),
                        )
                        if (pointingRight) {
                            Icon(
                                chevron,
                                contentDescription = null,
                                modifier = Modifier.size(CompactIosChevronSize),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun paneLocationName(destination: XEntry?, focusedDirId: String?): String {
    destination?.name?.takeIf { it.isNotBlank() }?.let { return it }
    val id = focusedDirId ?: return "…"
    if (id.startsWith("${XId.SCHEME_SMB}://")) {
        return Graph.smbConnections.displayLabelPathForId(id).substringAfterLast(" / ")
    }
    val raw = id.substringAfter("://")
    return when (raw) {
        "@user" -> stringResource(R.string.installed_apps)
        "@system" -> stringResource(R.string.system_apps)
        else -> raw.trimEnd('/').substringAfterLast('/')
            .substringAfterLast(XId.ARCHIVE_SEP)
            .ifEmpty {
                if (id.startsWith(XId.SCHEME_APPS)) stringResource(R.string.apps) else "/"
            }
    }
}

private fun paneLocationPath(destination: XEntry?, focusedDirId: String?): String {
    val id = destination?.id ?: focusedDirId ?: return "…"
    val path = id.substringAfter("://")
    return when {
        id.startsWith("${XId.SCHEME_SMB}://") ->
            Graph.smbConnections.displayLabelPathForId(id)
        id.startsWith("${XId.SCHEME_ROOT}://") -> "root:$path"
        else -> path.ifBlank { "/" }
    }
}

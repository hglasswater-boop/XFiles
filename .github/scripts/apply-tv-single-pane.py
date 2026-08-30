from pathlib import Path
import re

vm_path = Path("app/src/main/java/app/local1st/files/ui/main/MainViewModel.kt")
vm = vm_path.read_text()

if "import app.local1st.files.BuildConfig\n" not in vm:
    vm = vm.replace("import app.local1st.files.R\n", "import app.local1st.files.BuildConfig\nimport app.local1st.files.R\n", 1)

anchor = "class MainViewModel : ViewModel() {\n"
if anchor not in vm:
    raise SystemExit("MainViewModel class anchor not found")
vm = vm.replace(anchor, anchor + "    private val singlePaneEdition = BuildConfig.APPLICATION_ID.endsWith(\".tv\")\n", 1)

old = "    val activeCtrl: PaneController get() = panes[activePane.value]\n    val inactiveCtrl: PaneController get() = panes[1 - activePane.value]\n"
new = "    val activeCtrl: PaneController get() = panes[activePane.value]\n    val inactiveCtrl: PaneController get() =\n        if (singlePaneEdition) activeCtrl else panes[1 - activePane.value]\n    private val livePanes: List<PaneController>\n        get() = if (singlePaneEdition) listOf(activeCtrl) else panes\n"
if old not in vm:
    raise SystemExit("controller anchor not found")
vm = vm.replace(old, new, 1)

# TV keeps only the visible pane live. Mobile behavior is unchanged.
vm = vm.replace("panes.forEach", "livePanes.forEach")

old = "            activePane.value = session.activePane.coerceIn(0, panes.lastIndex)\n"
new = "            activePane.value = session.activePane.coerceIn(0, panes.lastIndex)\n"
if old not in vm:
    raise SystemExit("restore active pane anchor not found")
# Keep the saved TV pane as the one real pane; no migration/reset is needed.

old = """            if (wide) {
                coroutineScope {
                    panes.forEachIndexed { index, pane ->
                        launch {
                            background[index] = restorePane(index, pane, paneRoots, listings)
                        }
                    }
                }
                sessionReady.value = true
            } else {
"""
new = """            if (singlePaneEdition) {
                val active = activePane.value.coerceIn(0, panes.lastIndex)
                background[active] = restorePane(active, panes[active], paneRoots, listings)
                sessionReady.value = true
                awaitPaneInitialLayout(active, panes[active].state.value.treeVersion)
            } else if (wide) {
                coroutineScope {
                    panes.forEachIndexed { index, pane ->
                        launch {
                            background[index] = restorePane(index, pane, paneRoots, listings)
                        }
                    }
                }
                sessionReady.value = true
            } else {
"""
if old not in vm:
    raise SystemExit("restore critical paths anchor not found")
vm = vm.replace(old, new, 1)

old = "        startDirId = panes[1 - sourcePane].state.value.focusedDirId,\n"
new = "        startDirId = inactiveCtrl.state.value.focusedDirId,\n"
if old not in vm:
    raise SystemExit("transfer startDir anchor not found")
vm = vm.replace(old, new, 1)

old = """    fun setActivePane(index: Int) {
        activePane.value = index
    }
"""
new = """    fun setActivePane(index: Int) {
        // Google TV is intentionally a true single-pane browser. Keep the restored pane active
        // instead of waking the dormant second controller through pager/focus side effects.
        if (!singlePaneEdition) activePane.value = index
    }
"""
if old not in vm:
    raise SystemExit("setActivePane anchor not found")
vm = vm.replace(old, new, 1)

vm_path.write_text(vm)

screen_path = Path("app/src/main/java/app/local1st/files/ui/main/MainScreen.kt")
screen = screen_path.read_text()

old = """    val pendingTransfer by vm.pendingTransfer.collectAsStateWithLifecycle()
    val activeState by vm.panes[activePane].state.collectAsStateWithLifecycle()
    val otherPaneController = vm.panes[1 - activePane]
"""
new = """    val pendingTransfer by vm.pendingTransfer.collectAsStateWithLifecycle()
    val isTvEdition = BuildConfig.APPLICATION_ID.endsWith(\".tv\")
    val activeState by vm.panes[activePane].state.collectAsStateWithLifecycle()
    // TV uses the current pane as its operation destination. There is no hidden destination pane.
    val otherPaneController = if (isTvEdition) vm.activeCtrl else vm.panes[1 - activePane]
"""
if old not in screen:
    raise SystemExit("MainScreen state anchor not found")
screen = screen.replace(old, new, 1)

old = "    val isTvEdition = BuildConfig.APPLICATION_ID.endsWith(\".tv\")\n    val wideLayout = !isTvEdition && LocalConfiguration.current.screenWidthDp >= 700\n"
new = "    val wideLayout = !isTvEdition && LocalConfiguration.current.screenWidthDp >= 700\n"
if old not in screen:
    raise SystemExit("MainScreen duplicate TV anchor not found")
screen = screen.replace(old, new, 1)

old = """                        headerOverlay = {
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
"""
new = """                        headerOverlay = if (isTvEdition) {
                            null
                        } else {
                            {
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
                            }
                        },
"""
if old not in screen:
    raise SystemExit("header overlay anchor not found")
screen = screen.replace(old, new, 1)

old = """                HorizontalPager(
                    state = pagerState,
                    // TV intentionally uses this single-page path even on wide displays. The
                    // inactive pane remains available as an operation target without paying the
                    // cost of composing both pane trees at once.
                    beyondViewportPageCount = 0,
                    modifier = Modifier.fillMaxSize(),
"""
new = """                HorizontalPager(
                    state = pagerState,
                    // TV has one live pane only. Disable pager gestures so the dormant controller
                    // cannot be surfaced accidentally; mobile compact mode keeps normal paging.
                    userScrollEnabled = !isTvEdition,
                    beyondViewportPageCount = 0,
                    modifier = Modifier.fillMaxSize(),
"""
if old not in screen:
    raise SystemExit("pager anchor not found")
screen = screen.replace(old, new, 1)

screen_path.write_text(screen)

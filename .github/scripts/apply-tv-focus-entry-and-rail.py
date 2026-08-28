from pathlib import Path

pane_path = Path("app/src/tv/java/app/local1st/files/ui/browser/PaneView.kt")
pane = pane_path.read_text()
pane = pane.replace(
    "import androidx.compose.ui.focus.focusRequester\n",
    "import androidx.compose.ui.focus.focusProperties\nimport androidx.compose.ui.focus.focusRequester\n",
    1,
)
old_initial = '''    LaunchedEffect(isTv, active, displayNodes.size, searchActive) {
        if (
            isTv &&
            active &&
            !searchActive &&
            !tvInitialFocusRequested &&
            displayNodes.isNotEmpty()
        ) {
            withFrameNanos { }
            runCatching { tvInitialFocusRequester.requestFocus() }
            tvInitialFocusRequested = true
        }
    }
'''
new_initial = '''    LaunchedEffect(isTv, active, searchActive, displayNodes.firstOrNull()?.key) {
        if (
            isTv &&
            active &&
            !searchActive &&
            !tvInitialFocusRequested &&
            displayNodes.isNotEmpty()
        ) {
            val firstKey = displayNodes.first().key
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.any { it.key == firstKey }
            }.first { it }
            repeat(8) {
                withFrameNanos { }
                val focused = runCatching {
                    tvInitialFocusRequester.requestFocus()
                }.getOrDefault(false)
                if (focused) {
                    tvInitialFocusRequested = true
                    return@LaunchedEffect
                }
            }
        }
    }
'''
if old_initial not in pane:
    raise SystemExit("initial TV focus block not found")
pane = pane.replace(old_initial, new_initial, 1)
old_crumb = '''                    modifier = Modifier
                        .combinedClickable(
                            onClick = { onCrumbClick(id) },
                            onLongClick = { onCrumbLongClick(id, name) },
                        )
                        .padding(vertical = 4.dp, horizontal = 1.dp),
'''
new_crumb = '''                    modifier = Modifier
                        // Back handles parent navigation on TV. Keep breadcrumbs out of the
                        // D-pad focus graph so startup focus cannot get stranded in the header.
                        .focusProperties { canFocus = false }
                        .combinedClickable(
                            onClick = { onCrumbClick(id) },
                            onLongClick = { onCrumbLongClick(id, name) },
                        )
                        .padding(vertical = 4.dp, horizontal = 1.dp),
'''
if old_crumb not in pane:
    raise SystemExit("breadcrumb modifier block not found")
pane = pane.replace(old_crumb, new_crumb, 1)
pane_path.write_text(pane)

rail_path = Path("app/src/tv/java/app/local1st/files/ui/main/EditionSideActionMenu.kt")
rail = rail_path.read_text()
rail = rail.replace(
    "import androidx.compose.runtime.setValue\n",
    "import androidx.compose.runtime.setValue\nimport androidx.compose.runtime.withFrameNanos\n",
    1,
)
rail = rail.replace(
    "    var visible by remember { mutableStateOf(false) }\n    var railHadFocus by remember { mutableStateOf(false) }\n",
    "    var visible by remember { mutableStateOf(false) }\n    var railHadFocus by remember { mutableStateOf(false) }\n    var railHasFocus by remember { mutableStateOf(false) }\n",
    1,
)
old_focus = '''            .focusGroup()
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    railHadFocus = true
                } else if (railHadFocus) {
                    railHadFocus = false
                    visible = false
                }
            },
'''
new_focus = '''            .focusGroup()
            .onFocusChanged { state ->
                railHasFocus = state.hasFocus
                if (state.hasFocus) {
                    railHadFocus = true
                }
            },
'''
if old_focus not in rail:
    raise SystemExit("rail focus block not found")
rail = rail.replace(old_focus, new_focus, 1)
anchor = '''    LaunchedEffect(openRequest, firstEnabledIndex) {
        if (openRequest > 0 && firstEnabledIndex >= 0) {
            visible = true
        }
    }

'''
insert = '''    LaunchedEffect(openRequest, firstEnabledIndex) {
        if (openRequest > 0 && firstEnabledIndex >= 0) {
            visible = true
        }
    }

    // Moving focus between rail buttons can briefly report hasFocus=false on the parent.
    // Defer collapse for a few frames so Up/Down navigation can complete before deciding that
    // focus really left the rail.
    LaunchedEffect(railHasFocus, railHadFocus) {
        if (!railHasFocus && railHadFocus) {
            repeat(3) { withFrameNanos { } }
            if (!railHasFocus) {
                railHadFocus = false
                visible = false
            }
        }
    }

'''
if anchor not in rail:
    raise SystemExit("rail launch anchor not found")
rail = rail.replace(anchor, insert, 1)
old_request = '''            LaunchedEffect(visible, firstEnabledIndex) {
                if (visible && firstEnabledIndex >= 0) {
                    runCatching { actionRequesters[firstEnabledIndex].requestFocus() }
                }
            }
'''
new_request = '''            LaunchedEffect(visible, firstEnabledIndex) {
                if (visible && firstEnabledIndex >= 0) {
                    withFrameNanos { }
                    runCatching { actionRequesters[firstEnabledIndex].requestFocus() }
                }
            }
'''
if old_request not in rail:
    raise SystemExit("rail initial request block not found")
rail = rail.replace(old_request, new_request, 1)
rail_path.write_text(rail)

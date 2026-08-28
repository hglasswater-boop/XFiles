from pathlib import Path

pane_path = Path("app/src/tv/java/app/local1st/files/ui/browser/PaneView.kt")
pane = pane_path.read_text()

anchor = '''private const val SEARCH_DEBOUNCE_MS = 400L
private const val SEARCH_MIN_QUERY_LENGTH = 2
'''
replacement = '''private const val SEARCH_DEBOUNCE_MS = 400L
private const val SEARCH_MIN_QUERY_LENGTH = 2

// Side rails explicitly hand D-pad focus back to the active browser pane through this bridge.
private val tvBrowserFocusReturnRequest = mutableStateOf(0)

internal fun requestTvBrowserFocusReturn() {
    tvBrowserFocusReturnRequest.value += 1
}
'''
if anchor not in pane:
    raise SystemExit("constant anchor not found")
pane = pane.replace(anchor, replacement, 1)

state_anchor = '''    val tvRowFocusRequesters = remember(controller) { mutableMapOf<String, FocusRequester>() }
    var tvRequestedRowKey by remember(controller) { mutableStateOf<String?>(null) }
'''
state_replacement = '''    val tvRowFocusRequesters = remember(controller) { mutableMapOf<String, FocusRequester>() }
    var tvRequestedRowKey by remember(controller) { mutableStateOf<String?>(null) }
    var tvFocusedRowKey by remember(controller) { mutableStateOf<String?>(null) }
    val tvFocusReturnRequest = tvBrowserFocusReturnRequest.value
'''
if state_anchor not in pane:
    raise SystemExit("TV focus state anchor not found")
pane = pane.replace(state_anchor, state_replacement, 1)

launch_anchor = '''    LaunchedEffect(tvRequestedRowKey, displayNodes.map { it.key }) {
'''
return_effect = '''    LaunchedEffect(
        tvFocusReturnRequest,
        active,
        searchActive,
        displayNodes.firstOrNull()?.key,
    ) {
        if (
            tvFocusReturnRequest > 0 &&
            active &&
            !searchActive &&
            displayNodes.isNotEmpty()
        ) {
            tvRequestedRowKey = tvFocusedRowKey
                ?.takeIf { focused -> displayNodes.any { it.key == focused } }
                ?: displayNodes.first().key
        }
    }

    LaunchedEffect(tvRequestedRowKey, displayNodes.map { it.key }) {
'''
if launch_anchor not in pane:
    raise SystemExit("requested row effect anchor not found")
pane = pane.replace(launch_anchor, return_effect, 1)

surface_anchor = '''    Surface(
        modifier = modifier.fillMaxSize(),
        color = if (active) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
'''
# There are two similar Surfaces. Replace the last one, which is the actual browser surface.
pos = pane.rfind(surface_anchor)
if pos < 0:
    raise SystemExit("browser surface anchor not found")
surface_replacement = '''    Surface(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (isTv && active && !searchActive) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || displayNodes.isEmpty()) {
                            false
                        } else {
                            val focusedIndex = tvFocusedRowKey?.let { focusedKey ->
                                displayNodes.indexOfFirst { it.key == focusedKey }
                            } ?: -1
                            when (event.key) {
                                Key.DirectionDown -> {
                                    val targetIndex = if (focusedIndex >= 0) {
                                        (focusedIndex + 1).coerceAtMost(displayNodes.lastIndex)
                                    } else {
                                        0
                                    }
                                    tvRequestedRowKey = displayNodes[targetIndex].key
                                    true
                                }
                                Key.DirectionUp -> {
                                    val targetIndex = if (focusedIndex >= 0) {
                                        (focusedIndex - 1).coerceAtLeast(0)
                                    } else {
                                        0
                                    }
                                    tvRequestedRowKey = displayNodes[targetIndex].key
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
        color = if (active) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
'''
pane = pane[:pos] + pane[pos:].replace(surface_anchor, surface_replacement, 1)

old_row_focus = '''                                                    .onFocusChanged { focusState ->
                                                        if (
                                                            focusState.isFocused &&
                                                            tvRequestedRowKey == rawNode.key
                                                        ) {
                                                            tvRequestedRowKey = null
                                                        }
                                                    }
                                                    .onPreviewKeyEvent { event ->
                                                        if (event.type != KeyEventType.KeyDown) {
                                                            false
                                                        } else {
                                                            when (event.key) {
                                                                Key.DirectionDown -> {
                                                                    if (index < displayNodes.lastIndex) {
                                                                        tvRequestedRowKey =
                                                                            displayNodes[index + 1].key
                                                                    }
                                                                    true
                                                                }
                                                                Key.DirectionUp -> {
                                                                    if (index > 0) {
                                                                        tvRequestedRowKey =
                                                                            displayNodes[index - 1].key
                                                                    }
                                                                    true
                                                                }
                                                                else -> false
                                                            }
                                                        }
                                                    }
'''
new_row_focus = '''                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            tvFocusedRowKey = rawNode.key
                                                            if (tvRequestedRowKey == rawNode.key) {
                                                                tvRequestedRowKey = null
                                                            }
                                                        }
                                                    }
'''
if old_row_focus not in pane:
    raise SystemExit("row focus/key block not found")
pane = pane.replace(old_row_focus, new_row_focus, 1)
pane_path.write_text(pane)

rail_path = Path("app/src/tv/java/app/local1st/files/ui/main/EditionSideActionMenu.kt")
rail = rail_path.read_text()
import_anchor = '''import app.local1st.files.R
'''
import_replacement = '''import app.local1st.files.R
import app.local1st.files.ui.browser.requestTvBrowserFocusReturn
'''
if import_anchor not in rail:
    raise SystemExit("rail import anchor not found")
rail = rail.replace(import_anchor, import_replacement, 1)

key_anchor = '''                                            Key.DirectionUp -> {
                                                actions.indices
                                                    .lastOrNull { it < index && actions[it].enabled }
                                                    ?.let { previous -> actionRequesters[previous].requestFocus() }
                                                true
                                            }
                                            else -> false
'''
key_replacement = '''                                            Key.DirectionUp -> {
                                                actions.indices
                                                    .lastOrNull { it < index && actions[it].enabled }
                                                    ?.let { previous -> actionRequesters[previous].requestFocus() }
                                                true
                                            }
                                            Key.DirectionLeft -> {
                                                if (alignment == Alignment.CenterEnd) {
                                                    visible = false
                                                    railHadFocus = false
                                                    railHasFocus = false
                                                    requestTvBrowserFocusReturn()
                                                }
                                                true
                                            }
                                            Key.DirectionRight -> {
                                                if (alignment == Alignment.CenterStart) {
                                                    visible = false
                                                    railHadFocus = false
                                                    railHasFocus = false
                                                    requestTvBrowserFocusReturn()
                                                }
                                                true
                                            }
                                            else -> false
'''
if key_anchor not in rail:
    raise SystemExit("rail key anchor not found")
rail = rail.replace(key_anchor, key_replacement, 1)
rail_path.write_text(rail)

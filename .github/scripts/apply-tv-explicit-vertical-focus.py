from pathlib import Path

path = Path("app/src/tv/java/app/local1st/files/ui/browser/PaneView.kt")
text = path.read_text()

text = text.replace(
    "import androidx.compose.ui.focus.focusRequester\n",
    "import androidx.compose.ui.focus.focusRequester\nimport androidx.compose.ui.focus.onFocusChanged\n",
)
text = text.replace(
    "import androidx.compose.ui.platform.LocalContext\n",
    "import androidx.compose.ui.input.key.Key\n"
    "import androidx.compose.ui.input.key.KeyEventType\n"
    "import androidx.compose.ui.input.key.key\n"
    "import androidx.compose.ui.input.key.onPreviewKeyEvent\n"
    "import androidx.compose.ui.input.key.type\n"
    "import androidx.compose.ui.platform.LocalContext\n",
)

old = '''    val tvInitialFocusRequester = remember { FocusRequester() }
    var tvInitialFocusRequested by rememberSaveable(controller) { mutableStateOf(false) }
'''
new = '''    val tvInitialFocusRequester = remember { FocusRequester() }
    var tvInitialFocusRequested by rememberSaveable(controller) { mutableStateOf(false) }
    val tvRowFocusRequesters = remember(controller) { mutableMapOf<String, FocusRequester>() }
    var tvFocusedRowKey by remember(controller) { mutableStateOf<String?>(null) }
    var tvRequestedRowKey by remember(controller) { mutableStateOf<String?>(null) }
'''
if old not in text:
    raise SystemExit("TV focus state anchor not found")
text = text.replace(old, new, 1)

anchor = '''    LaunchedEffect(controller, listState, state.treeVersion) {
'''
insert = '''    LaunchedEffect(tvRequestedRowKey, displayNodes.map { it.key }) {
        val targetKey = tvRequestedRowKey ?: return@LaunchedEffect
        val targetIndex = displayNodes.indexOfFirst { it.key == targetKey }
        if (targetIndex < 0) {
            tvRequestedRowKey = null
            return@LaunchedEffect
        }

        if (listState.layoutInfo.visibleItemsInfo.none { it.key == targetKey }) {
            listState.scrollToItem(targetIndex)
        }
        withFrameNanos { }

        val requester = if (targetIndex == 0) {
            tvInitialFocusRequester
        } else {
            tvRowFocusRequesters.getOrPut(targetKey) { FocusRequester() }
        }
        runCatching { requester.requestFocus() }
    }

'''
if anchor not in text:
    raise SystemExit("LaunchedEffect insertion anchor not found")
text = text.replace(anchor, insert + anchor, 1)

old = '''                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (searchActive) Modifier.padding(top = listTopInset)
                                else Modifier,
                            ),
'''
new = '''                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (searchActive) Modifier.padding(top = listTopInset)
                                else Modifier,
                            )
                            .then(
                                if (isTv && active && !searchActive) {
                                    Modifier.onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) {
                                            false
                                        } else {
                                            val currentKey = tvRequestedRowKey ?: tvFocusedRowKey
                                            val currentIndex = displayNodes.indexOfFirst {
                                                it.key == currentKey
                                            }
                                            when (event.key) {
                                                Key.DirectionDown -> {
                                                    if (currentIndex >= 0 && currentIndex < displayNodes.lastIndex) {
                                                        tvRequestedRowKey = displayNodes[currentIndex + 1].key
                                                    }
                                                    currentIndex >= 0
                                                }
                                                Key.DirectionUp -> {
                                                    if (currentIndex > 0) {
                                                        tvRequestedRowKey = displayNodes[currentIndex - 1].key
                                                    }
                                                    currentIndex >= 0
                                                }
                                                else -> false
                                            }
                                        }
                                    }
                                } else {
                                    Modifier
                                },
                            ),
'''
if old not in text:
    raise SystemExit("LazyColumn modifier anchor not found")
text = text.replace(old, new, 1)

old = '''                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .then(
                                            if (isTv && active && index == 0) {
                                                Modifier.focusRequester(tvInitialFocusRequester)
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .then(
                                            if (itemAnimationsEnabled) Modifier.animateItem()
                                            else Modifier,
                                        ),
'''
new = '''                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .then(
                                            if (isTv && active) {
                                                val requester = if (index == 0) {
                                                    tvInitialFocusRequester
                                                } else {
                                                    tvRowFocusRequesters.getOrPut(rawNode.key) {
                                                        FocusRequester()
                                                    }
                                                }
                                                Modifier
                                                    .focusRequester(requester)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            tvFocusedRowKey = rawNode.key
                                                            if (tvRequestedRowKey == rawNode.key) {
                                                                tvRequestedRowKey = null
                                                            }
                                                        }
                                                    }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .then(
                                            if (itemAnimationsEnabled) Modifier.animateItem()
                                            else Modifier,
                                        ),
'''
if old not in text:
    raise SystemExit("EntryRow modifier anchor not found")
text = text.replace(old, new, 1)

path.write_text(text)

from pathlib import Path

path = Path("app/src/tv/java/app/local1st/files/ui/browser/PaneView.kt")
text = path.read_text()

text = text.replace(
    "    var tvFocusedRowKey by remember(controller) { mutableStateOf<String?>(null) }\n",
    "",
)

old_lazy = '''                            .then(
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
                            )'''
text = text.replace(old_lazy, "")

old_row = '''                                                Modifier
                                                    .focusRequester(requester)
                                                    .onFocusChanged { focusState ->
                                                        if (focusState.isFocused) {
                                                            tvFocusedRowKey = rawNode.key
                                                            if (tvRequestedRowKey == rawNode.key) {
                                                                tvRequestedRowKey = null
                                                            }
                                                        }
                                                    }
'''
new_row = '''                                                Modifier
                                                    .focusRequester(requester)
                                                    .onFocusChanged { focusState ->
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
if old_row not in text:
    raise SystemExit("row focus block not found")
text = text.replace(old_row, new_row, 1)

path.write_text(text)

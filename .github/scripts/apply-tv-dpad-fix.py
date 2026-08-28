from pathlib import Path

entry_path = Path("app/src/tv/java/app/local1st/files/ui/browser/EntryRow.kt")
text = entry_path.read_text()
if "import androidx.compose.ui.focus.focusProperties" not in text:
    text = text.replace(
        "import androidx.compose.ui.focus.onFocusChanged\n",
        "import androidx.compose.ui.focus.focusProperties\nimport androidx.compose.ui.focus.onFocusChanged\n",
    )
old = ".heightIn(min = rowMinHeight)\n                    .clickable(enabled = enabled, onClick = onToggleSelect),"
new = ".heightIn(min = rowMinHeight)\n                    // Keep each TV row as one D-pad focus target.\n                    .focusProperties { canFocus = false }\n                    .clickable(enabled = enabled, onClick = onToggleSelect),"
if old in text:
    text = text.replace(old, new, 1)
entry_path.write_text(text)

rail_path = Path("app/src/tv/java/app/local1st/files/ui/main/EditionSideActionMenu.kt")
text = rail_path.read_text()
if "import androidx.compose.ui.input.key.Key\n" not in text:
    text = text.replace(
        "import androidx.compose.ui.graphics.vector.ImageVector\n",
        "import androidx.compose.ui.graphics.vector.ImageVector\n"
        "import androidx.compose.ui.input.key.Key\n"
        "import androidx.compose.ui.input.key.KeyEventType\n"
        "import androidx.compose.ui.input.key.key\n"
        "import androidx.compose.ui.input.key.onPreviewKeyEvent\n"
        "import androidx.compose.ui.input.key.type\n",
    )
old = """    val firstEnabledIndex = actions.indexOfFirst { it.enabled }
    val firstActionRequester = remember(actions.size, firstEnabledIndex) { FocusRequester() }
"""
new = """    val firstEnabledIndex = actions.indexOfFirst { it.enabled }
    val enabledSignature = actions.map { it.enabled }
    val actionRequesters = remember(actions.size, enabledSignature) {
        List(actions.size) { FocusRequester() }
    }
"""
if old in text:
    text = text.replace(old, new, 1)
old = """                    actions.forEachIndexed { index, action ->
                        TvActionButton(
                            action = action,
                            modifier = if (index == firstEnabledIndex) {
                                Modifier.focusRequester(firstActionRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
"""
new = """                    actions.forEachIndexed { index, action ->
                        TvActionButton(
                            action = action,
                            modifier = Modifier
                                .focusRequester(actionRequesters[index])
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        false
                                    } else {
                                        when (event.key) {
                                            Key.DirectionDown -> {
                                                actions.indices
                                                    .firstOrNull { it > index && actions[it].enabled }
                                                    ?.let { next -> actionRequesters[next].requestFocus() }
                                                true
                                            }
                                            Key.DirectionUp -> {
                                                actions.indices
                                                    .lastOrNull { it < index && actions[it].enabled }
                                                    ?.let { previous -> actionRequesters[previous].requestFocus() }
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                },
                        )
                    }
"""
if old in text:
    text = text.replace(old, new, 1)
old = """                if (visible && firstEnabledIndex >= 0) {
                    runCatching { firstActionRequester.requestFocus() }
                }
"""
new = """                if (visible && firstEnabledIndex >= 0) {
                    runCatching { actionRequesters[firstEnabledIndex].requestFocus() }
                }
"""
if old in text:
    text = text.replace(old, new, 1)
rail_path.write_text(text)

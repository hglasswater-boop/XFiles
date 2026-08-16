from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# Toolbar copy/move must open the destination picker instead of executing immediately.
main_screen = Path("app/src/main/java/app/local1st/files/ui/main/MainScreen.kt")
s = main_screen.read_text()
s = replace_once(
    s,
    "vm.copySelection(move = false)",
    "vm.chooseTransferDestination(move = false)",
    "copy toolbar action",
)
s = replace_once(
    s,
    "vm.copySelection(move = true)",
    "vm.chooseTransferDestination(move = true)",
    "move toolbar action",
)
main_screen.write_text(s)


picker = Path("app/src/main/java/app/local1st/files/ui/dialogs/DestinationPickerScreen.kt")
s = picker.read_text()
s = replace_once(
    s,
    """/**
 * Optional full-screen folder chooser reached from an entry's long-press menu. The primary
 * copy/move actions use the other pane directly; this screen is the explicit-location escape hatch.
 */""",
    """/**
 * Full-screen destination chooser used by copy/move actions. The user can jump to either
 * pane's current folder, browse to any writable directory, and explicitly confirm the transfer.
 */""",
    "picker comment",
)

anchor = """    var reloadTick by remember { mutableStateOf(0) }
    var nameDialog by remember { mutableStateOf(false) }

    fun goUp(from: XEntry) {
"""
replacement = """    var reloadTick by remember { mutableStateOf(0) }
    var nameDialog by remember { mutableStateOf(false) }

    // Snapshot both pane locations when the picker opens. This keeps the source/target shortcuts
    // stable while the user browses around inside the picker.
    val sourcePaneDirId = remember(t, vm) { vm.activeCtrl.state.value.focusedDirId }
    val otherPaneDirId = remember(t, vm) { vm.inactiveCtrl.state.value.focusedDirId }

    fun jumpTo(dirId: String?) {
        scope.launch {
            current = dirId?.let { id ->
                withContext(Dispatchers.IO) {
                    runCatching { Graph.fsRegistry.forId(id).stat(id) }.getOrNull()
                }
            }
        }
    }

    fun goUp(from: XEntry) {
"""
s = replace_once(s, anchor, replacement, "pane shortcut state")

anchor = """            Text(
                current?.let { pathLabel(it) } ?: stringResource(R.string.this_device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            HorizontalDivider()
"""
replacement = """            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { jumpTo(sourcePaneDirId) },
                    enabled = sourcePaneDirId != null,
                ) { Text(\"元ペイン\") }
                OutlinedButton(
                    onClick = { jumpTo(otherPaneDirId) },
                    enabled = otherPaneDirId != null,
                ) { Text(\"別ペイン\") }
            }

            Text(
                current?.let { pathLabel(it) } ?: stringResource(R.string.this_device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            HorizontalDivider()
"""
s = replace_once(s, anchor, replacement, "pane shortcut buttons")
picker.write_text(s)


readme = Path("README.md")
s = readme.read_text()
anchor = """### Video player improvements
"""
section = """### Copy / move destination confirmation

- Copy and move from a selection no longer start immediately when the toolbar action is tapped.
- The destination picker can jump to either the **source pane's current folder** or the
  **other pane's current folder**, then browse deeper before committing the operation.
- The transfer starts only after tapping **Copy here** / **Move here**, which prevents accidental
  transfers to the wrong pane or folder.

### Video player improvements
"""
s = replace_once(s, anchor, section, "README transfer section")
readme.write_text(s)

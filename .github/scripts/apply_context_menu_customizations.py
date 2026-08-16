from pathlib import Path

NEW_CONTEXT_MENU_SETTINGS = '''package app.local1st.files.core.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted order for context-menu actions. */
object ContextMenuOrderSettings {
    const val DETAILS = "details"
    const val FOLDER_SORT = "folder_sort"
    const val NEW_TEXT_FILE = "new_text_file"
    const val FAVORITE = "favorite"
    const val OPEN_WITH = "open_with"
    const val SHARE = "share"
    const val COPY_TO = "copy_to"
    const val MOVE_TO = "move_to"
    const val ZIP = "zip"
    const val EXTRACT = "extract"
    const val INSTALL = "install"
    const val RENAME = "rename"
    const val DELETE = "delete"

    val DEFAULT_ORDER = listOf(
        DETAILS, FOLDER_SORT, NEW_TEXT_FILE, FAVORITE, OPEN_WITH, SHARE,
        COPY_TO, MOVE_TO, ZIP, EXTRACT, INSTALL, RENAME, DELETE,
    )

    private const val PREFS_NAME = "context_menu_order"
    private const val KEY_ORDER = "item_order"
    private val _order = MutableStateFlow(DEFAULT_ORDER)
    private var initialized = false

    fun order(context: Context): StateFlow<List<String>> {
        ensureInitialized(context)
        return _order.asStateFlow()
    }

    fun current(context: Context): List<String> {
        ensureInitialized(context)
        return _order.value
    }

    fun set(context: Context, value: List<String>) {
        ensureInitialized(context)
        val normalized = normalize(value)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDER, normalized.joinToString(",")).apply()
        _order.value = normalized
    }

    fun move(context: Context, id: String, delta: Int) {
        val items = current(context).toMutableList()
        val from = items.indexOf(id)
        if (from < 0) return
        val to = (from + delta).coerceIn(items.indices)
        if (from == to) return
        items.removeAt(from)
        items.add(to, id)
        set(context, items)
    }

    fun reset(context: Context) = set(context, DEFAULT_ORDER)

    @Synchronized
    private fun ensureInitialized(context: Context) {
        if (initialized) return
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ORDER, null)?.split(',').orEmpty()
        _order.value = normalize(stored)
        initialized = true
    }

    private fun normalize(value: List<String>): List<String> {
        val known = DEFAULT_ORDER.toSet()
        val kept = value.filter { it in known }.distinct()
        return kept + DEFAULT_ORDER.filterNot { it in kept }
    }
}
'''

NEW_DIALOG_HELPERS = '''@Composable
private fun ContextMenuColumn(
    order: List<String>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val rank = order.withIndex().associate { it.value to it.index }
        val orderablePositions = measurables.indices.filter { index ->
            (measurables[index].layoutId as? String) in rank
        }
        val sortedIndices = orderablePositions
            .map { index -> index to (measurables[index].layoutId as String) }
            .sortedBy { (_, id) -> rank[id] ?: Int.MAX_VALUE }
            .map { (index, _) -> index }
        val visualOrder = measurables.indices.toMutableList()
        orderablePositions.forEachIndexed { sortedPosition, originalPosition ->
            visualOrder[originalPosition] = sortedIndices[sortedPosition]
        }
        val contentWidth = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val contentHeight = visualOrder.sumOf { placeables[it].height }
        layout(
            constraints.constrainWidth(contentWidth),
            constraints.constrainHeight(contentHeight),
        ) {
            var y = 0
            visualOrder.forEach { index ->
                val placeable = placeables[index]
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

@Composable
private fun contextMenuOrderKey(label: String): String? = when (label) {
    stringResource(R.string.details) -> ContextMenuOrderSettings.DETAILS
    "このフォルダの並び順" -> ContextMenuOrderSettings.FOLDER_SORT
    stringResource(R.string.new_text_file) -> ContextMenuOrderSettings.NEW_TEXT_FILE
    stringResource(R.string.add_to_favorites), stringResource(R.string.remove_from_favorites) -> ContextMenuOrderSettings.FAVORITE
    stringResource(R.string.open_with) -> ContextMenuOrderSettings.OPEN_WITH
    stringResource(R.string.share) -> ContextMenuOrderSettings.SHARE
    stringResource(R.string.copy_to) -> ContextMenuOrderSettings.COPY_TO
    stringResource(R.string.move_to) -> ContextMenuOrderSettings.MOVE_TO
    stringResource(R.string.zip) -> ContextMenuOrderSettings.ZIP
    stringResource(R.string.extract_to_other_pane) -> ContextMenuOrderSettings.EXTRACT
    stringResource(R.string.install) -> ContextMenuOrderSettings.INSTALL
    stringResource(R.string.rename) -> ContextMenuOrderSettings.RENAME
    stringResource(R.string.delete), "削除" -> ContextMenuOrderSettings.DELETE
    else -> null
}

@Composable
private fun MenuItem(
    label: String,
    enabled: Boolean = true,
    disabledReason: String? = null,
    onClick: () -> Unit,
) {
    val textColor = if (enabled) {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    } else {
        androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val orderKey = contextMenuOrderKey(label)
    val orderModifier = if (orderKey != null) Modifier.layoutId(orderKey) else Modifier
    Column(
        orderModifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
    ) {
        Text(label, color = textColor)
        if (!enabled && disabledReason != null) {
            Text(
                disabledReason,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = textColor,
            )
        }
    }
}
'''

NEW_SETTINGS_FUNCS = '''@Composable
private fun ContextMenuOrderSection() {
    val context = LocalContext.current
    val order by ContextMenuOrderSettings.order(context).collectAsState()
    var showEditor by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("コンテキストメニュー", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "ファイル／フォルダを長押ししたときの項目順を変更できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showEditor = true }) { Text("並び順を編集") }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("コンテキストメニューの並び順") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    order.forEachIndexed { index, id ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                contextMenuOrderLabel(id),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(
                                enabled = index > 0,
                                onClick = { ContextMenuOrderSettings.move(context, id, -1) },
                            ) { Text("↑") }
                            TextButton(
                                enabled = index < order.lastIndex,
                                onClick = { ContextMenuOrderSettings.move(context, id, 1) },
                            ) { Text("↓") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEditor = false }) { Text("閉じる") } },
            dismissButton = {
                TextButton(onClick = { ContextMenuOrderSettings.reset(context) }) {
                    Text("初期順に戻す")
                }
            },
        )
    }
}

@Composable
private fun contextMenuOrderLabel(id: String): String = when (id) {
    ContextMenuOrderSettings.DETAILS -> stringResource(R.string.details)
    ContextMenuOrderSettings.FOLDER_SORT -> "このフォルダの並び順"
    ContextMenuOrderSettings.NEW_TEXT_FILE -> stringResource(R.string.new_text_file)
    ContextMenuOrderSettings.FAVORITE -> "お気に入りに追加／解除"
    ContextMenuOrderSettings.OPEN_WITH -> stringResource(R.string.open_with)
    ContextMenuOrderSettings.SHARE -> stringResource(R.string.share)
    ContextMenuOrderSettings.COPY_TO -> stringResource(R.string.copy_to)
    ContextMenuOrderSettings.MOVE_TO -> stringResource(R.string.move_to)
    ContextMenuOrderSettings.ZIP -> stringResource(R.string.zip)
    ContextMenuOrderSettings.EXTRACT -> stringResource(R.string.extract_to_other_pane)
    ContextMenuOrderSettings.INSTALL -> stringResource(R.string.install)
    ContextMenuOrderSettings.RENAME -> stringResource(R.string.rename)
    ContextMenuOrderSettings.DELETE -> stringResource(R.string.delete)
    else -> id
}

'''

FINAL_WORKFLOW = '''name: Debug CI

on:
  push:
  workflow_dispatch:

concurrency:
  group: debug-ci-${{ github.ref }}
  cancel-in-progress: true

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4

      - name: Require stable signing secrets
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          set -eu
          test -n "$KEYSTORE_BASE64"
          test -n "$KEYSTORE_PASSWORD"
          test -n "$KEY_ALIAS"
          test -n "$KEY_PASSWORD"

      - name: Decode stable signing key
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/xfiles-personal.jks"

      - name: Compile and test
        env:
          XFILES_KEYSTORE: ${{ runner.temp }}/xfiles-personal.jks
          XFILES_KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          XFILES_KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          XFILES_KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: ./gradlew :app:assembleDebug :app:testDebugUnitTest -PbuildNumber=${{ github.run_number }} --no-daemon

      - name: Name debug APK
        id: apk_name
        run: |
          set -eu
          VERSION=$(sed -n 's/^versionName=//p' version.properties | tr -d '[:space:]')
          APK_NAME="XFiles-${VERSION}.apk"
          cp app/build/outputs/apk/debug/app-debug.apk "$APK_NAME"
          echo "name=$APK_NAME" >> "$GITHUB_OUTPUT"

      - name: Upload signed debug APK
        uses: actions/upload-artifact@v4
        with:
          name: XFiles-${{ github.run_number }}-signed-debug
          path: ${{ steps.apk_name.outputs.name }}
          if-no-files-found: error
          retention-days: 30
'''

prefs = Path("app/src/main/java/app/local1st/files/core/prefs/ContextMenuOrderSettings.kt")
prefs.write_text(NEW_CONTEXT_MENU_SETTINGS)

dialogs = Path("app/src/main/java/app/local1st/files/ui/dialogs/Dialogs.kt")
text = dialogs.read_text()
if "import androidx.compose.foundation.clickable\n" not in text:
    text = text.replace("import androidx.compose.foundation.layout.Column\n", "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Column\n", 1)
if "import androidx.compose.ui.layout.layoutId\n" not in text:
    text = text.replace("import androidx.compose.ui.focus.focusRequester\n", "import androidx.compose.ui.focus.focusRequester\nimport androidx.compose.ui.layout.layoutId\n", 1)
if "import app.local1st.files.core.prefs.ContextMenuOrderSettings\n" not in text:
    text = text.replace("import app.local1st.files.core.prefs.FolderSortSpec\n", "import app.local1st.files.core.prefs.ContextMenuOrderSettings\nimport app.local1st.files.core.prefs.FolderSortSpec\n", 1)
marker = "    val context = Graph.appContext\n"
if "val contextMenuOrder by ContextMenuOrderSettings.order(context).collectAsState()" not in text:
    assert marker in text
    text = text.replace(marker, marker + "    val contextMenuOrder by ContextMenuOrderSettings.order(context).collectAsState()\n", 1)
column_marker = "    Column(Modifier.padding(bottom = 24.dp)) {\n"
assert column_marker in text
text = text.replace(column_marker, "    ContextMenuColumn(contextMenuOrder, Modifier.padding(bottom = 24.dp)) {\n", 1)
tail_marker = "@Composable\nprivate fun MenuItem("
text = text[:text.index(tail_marker)] + NEW_DIALOG_HELPERS
dialogs.write_text(text)

settings = Path("app/src/main/java/app/local1st/files/ui/settings/SettingsScreen.kt")
text = settings.read_text()
if "import androidx.compose.foundation.layout.heightIn\n" not in text:
    text = text.replace("import androidx.compose.foundation.layout.height\n", "import androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.heightIn\n", 1)
if "import androidx.compose.material3.AlertDialog\n" not in text:
    text = text.replace("import androidx.compose.material3.Card\n", "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Card\n", 1)
if "import androidx.compose.material3.TextButton\n" not in text:
    text = text.replace("import androidx.compose.material3.Text\n", "import androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n", 1)
if "import app.local1st.files.core.prefs.ContextMenuOrderSettings\n" not in text:
    text = text.replace("import app.local1st.files.core.prefs.DEFAULT_ROOT_ENABLED\n", "import app.local1st.files.core.prefs.ContextMenuOrderSettings\nimport app.local1st.files.core.prefs.DEFAULT_ROOT_ENABLED\n", 1)
if "                ContextMenuOrderSection()\n" not in text:
    text = text.replace("                BrowserDisplaySettingsSection()\n", "                BrowserDisplaySettingsSection()\n                ContextMenuOrderSection()\n", 1)
func_marker = "@StringRes\nprivate fun activeTransportLabelRes"
assert func_marker in text
if "private fun ContextMenuOrderSection()" not in text:
    text = text.replace(func_marker, NEW_SETTINGS_FUNCS + func_marker, 1)
settings.write_text(text)

backup = Path("app/src/main/java/app/local1st/files/core/prefs/SettingsBackup.kt")
text = backup.read_text()
display_block = '''        val displayJson = JSONObject()
            .put("thumbnailSize", display.thumbnailSize.name)
            .put("filenameMode", display.filenameMode.name)
            .put("treeLevels", display.treeLevels)
'''
if "val contextMenuOrder = JSONArray().apply" not in text:
    assert display_block in text
    text = text.replace(display_block, display_block + '''
        val contextMenuOrder = JSONArray().apply {
            ContextMenuOrderSettings.current(context).forEach { id -> put(id) }
        }
''', 1)
if '.put("contextMenuOrder", contextMenuOrder)' not in text:
    text = text.replace('            .put("browserDisplay", displayJson)\n', '            .put("browserDisplay", displayJson)\n            .put("contextMenuOrder", contextMenuOrder)\n', 1)
import_marker = '''        root.optJSONObject("browserDisplay")?.let { display ->
            BrowserDisplaySettings.setThumbnailSize(
                context,
                enumValueOrDefault(display.optString("thumbnailSize"), ThumbnailSize.MEDIUM),
            )
            BrowserDisplaySettings.setFilenameMode(
                context,
                enumValueOrDefault(display.optString("filenameMode"), FilenameDisplayMode.TWO_LINES),
            )
            BrowserDisplaySettings.setTreeLevels(context, display.optInt("treeLevels", 4))
        }
'''
if 'root.optJSONArray("contextMenuOrder")' not in text:
    assert import_marker in text
    text = text.replace(import_marker, import_marker + '''
        root.optJSONArray("contextMenuOrder")?.let { array ->
            val restored = buildList {
                for (index in 0 until array.length()) {
                    val id = array.optString(index)
                    if (id.isNotBlank()) add(id)
                }
            }
            ContextMenuOrderSettings.set(context, restored)
        }
''', 1)
backup.write_text(text)

Path(".github/workflows/personal-fork-ci.yml").write_text(FINAL_WORKFLOW)
Path(".github/scripts/apply_context_menu_customizations.py").unlink()

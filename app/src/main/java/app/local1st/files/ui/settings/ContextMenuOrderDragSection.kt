package app.local1st.files.ui.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.local1st.files.R
import app.local1st.files.core.prefs.ContextMenuOrderSettings

/** Context-menu order editor with both button and long-press drag reordering. */
@Composable
internal fun ReorderableContextMenuOrderSection() {
    val context = LocalContext.current
    val order by ContextMenuOrderSettings.order(context).collectAsState()
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

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
            onDismissRequest = {
                draggedId = null
                dragOffsetY = 0f
                showEditor = false
            },
            title = { Text("コンテキストメニューの並び順") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "項目を長押しして上下にドラッグできます。↑↓ボタンでも移動できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    order.forEachIndexed { index, id ->
                        key(id) {
                            var rowHeightPx by remember { mutableIntStateOf(1) }
                            val dragging = draggedId == id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (dragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (dragging) dragOffsetY else 0f
                                        alpha = if (dragging) 0.88f else 1f
                                    }
                                    .onSizeChanged { rowHeightPx = it.height.coerceAtLeast(1) }
                                    .pointerInput(id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggedId = id
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                draggedId = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggedId = null
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                if (draggedId != id) return@detectDragGesturesAfterLongPress
                                                dragOffsetY += dragAmount.y
                                                val rowHeight = rowHeightPx.toFloat().coerceAtLeast(1f)
                                                val threshold = rowHeight * 0.5f

                                                while (dragOffsetY >= threshold) {
                                                    val items = ContextMenuOrderSettings.current(context)
                                                    val from = items.indexOf(id)
                                                    if (from < 0 || from >= items.lastIndex) {
                                                        dragOffsetY = threshold
                                                        break
                                                    }
                                                    ContextMenuOrderSettings.move(context, id, 1)
                                                    // The row itself just moved down by one height. Compensate so
                                                    // it stays visually attached to the user's finger.
                                                    dragOffsetY -= rowHeight
                                                }
                                                while (dragOffsetY <= -threshold) {
                                                    val items = ContextMenuOrderSettings.current(context)
                                                    val from = items.indexOf(id)
                                                    if (from <= 0) {
                                                        dragOffsetY = -threshold
                                                        break
                                                    }
                                                    ContextMenuOrderSettings.move(context, id, -1)
                                                    dragOffsetY += rowHeight
                                                }
                                            },
                                        )
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "☰",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (dragging) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                                Text(
                                    reorderContextMenuLabel(id),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(
                                    enabled = !dragging && index > 0,
                                    onClick = { ContextMenuOrderSettings.move(context, id, -1) },
                                ) { Text("↑") }
                                TextButton(
                                    enabled = !dragging && index < order.lastIndex,
                                    onClick = { ContextMenuOrderSettings.move(context, id, 1) },
                                ) { Text("↓") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        draggedId = null
                        dragOffsetY = 0f
                        showEditor = false
                    },
                ) { Text("閉じる") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        draggedId = null
                        dragOffsetY = 0f
                        ContextMenuOrderSettings.reset(context)
                    },
                ) { Text("初期順に戻す") }
            },
        )
    }
}

@Composable
private fun reorderContextMenuLabel(id: String): String = when (id) {
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

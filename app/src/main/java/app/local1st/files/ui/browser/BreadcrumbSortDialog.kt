package app.local1st.files.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.prefs.FolderSortSpec
import app.local1st.files.core.prefs.SortBy
import app.local1st.files.di.Graph

/** Folder-sort editor opened directly by long-pressing a breadcrumb segment. */
@Composable
internal fun BreadcrumbSortDialog(
    folderId: String,
    folderName: String,
    onDismiss: () -> Unit,
) {
    val overrides by Graph.folderSorts.sorts.collectAsState()
    val globalBy by Graph.settings.sortBy.collectAsState(initial = SortBy.NAME)
    val globalDescending by Graph.settings.sortDescending.collectAsState(initial = false)
    val globalDirsFirst by Graph.settings.dirsFirst.collectAsState(initial = true)
    val current = overrides[folderId]

    var by by remember(folderId, current, globalBy) { mutableStateOf(current?.by ?: globalBy) }
    var descending by remember(folderId, current, globalDescending) {
        mutableStateOf(current?.descending ?: globalDescending)
    }
    var dirsFirst by remember(folderId, current, globalDirsFirst) {
        mutableStateOf(current?.dirsFirst ?: globalDirsFirst)
    }

    fun sortLabel(value: SortBy): String = when (value) {
        SortBy.NAME -> "名前"
        SortBy.SIZE -> "サイズ"
        SortBy.DATE -> "更新日時"
        SortBy.TYPE -> "種類"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("このフォルダの並び順") },
        text = {
            Column {
                Text(folderName)
                SortBy.entries.forEach { option ->
                    TextButton(
                        onClick = { by = option },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (option == by) "● ${sortLabel(option)}" else "○ ${sortLabel(option)}")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                TextButton(
                    onClick = { descending = !descending },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (descending) "順序: 降順" else "順序: 昇順")
                }
                TextButton(
                    onClick = { dirsFirst = !dirsFirst },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (dirsFirst) "フォルダを先頭: ON" else "フォルダを先頭: OFF")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    Graph.folderSorts.set(folderId, FolderSortSpec(by, descending, dirsFirst))
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Column {
                TextButton(
                    onClick = {
                        Graph.folderSorts.set(folderId, null)
                        onDismiss()
                    },
                ) { Text("全体設定を使用") }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

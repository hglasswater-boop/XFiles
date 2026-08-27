package app.local1st.files.ui.main

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R

private data class TvRailAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

private data class TvRailOpenRequest(
    val left: Int = 0,
    val right: Int = 0,
)

private val tvRailOpenRequest = mutableStateOf(TvRailOpenRequest())

internal fun requestEditionSideRail(left: Boolean) {
    val current = tvRailOpenRequest.value
    tvRailOpenRequest.value = if (left) {
        current.copy(left = current.left + 1)
    } else {
        current.copy(right = current.right + 1)
    }
}

@Composable
internal fun EditionSideActionMenu(
    selectionCount: Int,
    selectionAvailable: Boolean,
    canUseOtherPane: Boolean,
    canShareSelection: Boolean,
    copyTargetLabel: String,
    moveTargetLabel: String,
    compressTargetLabel: String,
    transferActive: Boolean,
    transferMove: Boolean,
    transferSourceCount: Int,
    transferDestinationName: String,
    canConfirmTransfer: Boolean,
    onNewFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onMore: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCompress: () -> Unit,
    onShare: () -> Unit,
    onCancelTransfer: () -> Unit,
    onConfirmTransfer: () -> Unit,
) {
    val leftStatus = when {
        transferActive -> transferSourceCount.toString()
        selectionCount > 0 -> selectionCount.toString()
        else -> null
    }
    val leftActions = when {
        transferActive -> listOf(
            TvRailAction(
                stringResource(R.string.cancel),
                Icons.Outlined.Close,
                onClick = onCancelTransfer,
            ),
        )
        selectionCount > 0 -> listOf(
            TvRailAction(stringResource(R.string.clear), Icons.Outlined.Close, onClick = onClear),
            TvRailAction(copyTargetLabel, Icons.Outlined.ContentCopy, selectionAvailable, onCopy),
            TvRailAction(
                moveTargetLabel,
                Icons.AutoMirrored.Outlined.DriveFileMove,
                selectionAvailable,
                onMove,
            ),
        )
        else -> listOf(
            TvRailAction(
                stringResource(R.string.new_folder),
                Icons.Outlined.CreateNewFolder,
                onClick = onNewFolder,
            ),
            TvRailAction(
                stringResource(R.string.new_text_file),
                Icons.AutoMirrored.Outlined.NoteAdd,
                onClick = onNewTextFile,
            ),
            TvRailAction(stringResource(R.string.search), Icons.Outlined.Search, onClick = onSearch),
        )
    }
    val rightActions = when {
        transferActive -> listOf(
            TvRailAction(
                label = if (transferMove) {
                    "${stringResource(R.string.move_to)} $transferDestinationName"
                } else {
                    "${stringResource(R.string.copy_to)} $transferDestinationName"
                },
                icon = if (transferMove) {
                    Icons.AutoMirrored.Outlined.DriveFileMove
                } else {
                    Icons.Outlined.ContentCopy
                },
                enabled = canConfirmTransfer,
                onClick = onConfirmTransfer,
            ),
        )
        selectionCount > 0 -> listOf(
            TvRailAction(
                stringResource(R.string.delete),
                Icons.Outlined.Delete,
                selectionAvailable,
                onDelete,
            ),
            TvRailAction(
                compressTargetLabel,
                Icons.Outlined.Archive,
                canUseOtherPane && selectionAvailable,
                onCompress,
            ),
            TvRailAction(
                stringResource(
                    if (canShareSelection) R.string.share else R.string.share_requires_local_files,
                ),
                Icons.Outlined.Share,
                canShareSelection,
                onShare,
            ),
        )
        else -> listOf(
            TvRailAction(stringResource(R.string.refresh), Icons.Outlined.Refresh, onClick = onRefresh),
            TvRailAction(stringResource(R.string.settings), Icons.Outlined.Settings, onClick = onSettings),
            TvRailAction(stringResource(R.string.more), Icons.Outlined.MoreVert, onClick = onMore),
        )
    }

    val railOpenRequest = tvRailOpenRequest.value
    Box(Modifier.fillMaxSize()) {
        FocusRevealRail(
            alignment = Alignment.CenterStart,
            status = leftStatus,
            actions = leftActions,
            openRequest = railOpenRequest.left,
        )
        FocusRevealRail(
            alignment = Alignment.CenterEnd,
            actions = rightActions,
            openRequest = railOpenRequest.right,
        )
    }
}

@Composable
private fun BoxScope.FocusRevealRail(
    alignment: Alignment,
    status: String? = null,
    actions: List<TvRailAction>,
    openRequest: Int,
) {
    var visible by remember { mutableStateOf(false) }
    var railHadFocus by remember { mutableStateOf(false) }
    val firstEnabledIndex = actions.indexOfFirst { it.enabled }
    val firstActionRequester = remember(actions.size, firstEnabledIndex) { FocusRequester() }

    LaunchedEffect(openRequest, firstEnabledIndex) {
        if (openRequest > 0 && firstEnabledIndex >= 0) {
            visible = true
        }
    }

    Box(
        modifier = Modifier
            .align(alignment)
            .fillMaxHeight()
            .width(if (visible) 104.dp else 0.dp)
            .focusGroup()
            .onFocusChanged { state ->
                if (state.hasFocus) {
                    railHadFocus = true
                } else if (railHadFocus) {
                    railHadFocus = false
                    visible = false
                }
            },
    ) {
        if (visible) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp, vertical = 44.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.98f),
                tonalElevation = 5.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 7.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (status != null) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    actions.forEachIndexed { index, action ->
                        TvActionButton(
                            action = action,
                            modifier = if (index == firstEnabledIndex) {
                                Modifier.focusRequester(firstActionRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
            LaunchedEffect(visible, firstEnabledIndex) {
                if (visible && firstEnabledIndex >= 0) {
                    runCatching { firstActionRequester.requestFocus() }
                }
            }
        }
    }
}

@Composable
private fun TvActionButton(
    action: TvRailAction,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        color = if (focused) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = when {
            !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            focused -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(action.icon, contentDescription = null)
            Text(
                text = action.label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
            )
        }
    }
}

package app.local1st.files.ui.main

import androidx.compose.runtime.Composable

@Suppress("UNUSED_PARAMETER")
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
    onMore: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onCompress: () -> Unit,
    onShare: () -> Unit,
    onCancelTransfer: () -> Unit,
    onConfirmTransfer: () -> Unit,
) = Unit

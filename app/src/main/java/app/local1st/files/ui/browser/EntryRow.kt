package app.local1st.files.ui.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.local1st.files.R
import app.local1st.files.core.fs.EntryKind
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.prefs.BrowserDisplayConfig
import app.local1st.files.core.prefs.BrowserDisplaySettings
import app.local1st.files.core.prefs.FilenameDisplayMode
import app.local1st.files.core.thumb.AppIcon
import app.local1st.files.core.thumb.PrivFile
import app.local1st.files.core.thumb.RemoteFile
import app.local1st.files.core.thumb.RemoteVideoThumb
import app.local1st.files.core.thumb.VideoThumb
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.core.util.Format
import app.local1st.files.di.Graph
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.File

// One visible tree level. Deeper paths are visually compressed according to the display setting;
// the breadcrumb still carries the full path, so the list does not need unlimited indentation.
private val IndentWidth = 12.dp
private val BaseRowHeight = 48.dp
private val SelectionWidth = 36.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EntryRow(
    node: TreeNode,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    enabled: Boolean = true,
    richContent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val entry = node.entry
    val display by BrowserDisplaySettings.state(Graph.appContext).collectAsState()
    val wantsThumbnail = EntryIcons.wantsThumbnail(entry)
    val displayDepth = minOf(node.depth, display.treeLevels)
    val rowMinHeight = if (wantsThumbnail) {
        maxOf(48, display.thumbnailSize.heightDp + 8).dp
    } else {
        BaseRowHeight
    }
    val isVolume = entry.kind == EntryKind.VOLUME_INTERNAL ||
        entry.kind == EntryKind.VOLUME_SD ||
        entry.kind == EntryKind.VOLUME_USB
    val selectable = !isVolume &&
        entry.kind != EntryKind.APPS_ROOT &&
        entry.kind != EntryKind.ROOT &&
        entry.kind != EntryKind.APP_COMPONENT_GROUP &&
        entry.kind != EntryKind.APP_COMPONENT

    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        focused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    if (!richContent) {
        StartupEntryRow(
            node = node,
            selected = selected,
            focused = focused,
            onClick = onClick,
            enabled = enabled,
            selectable = selectable,
            isVolume = isVolume,
            display = display,
            wantsThumbnail = wantsThumbnail,
            displayDepth = displayDepth,
            rowMinHeight = rowMinHeight,
            modifier = modifier,
        )
        return
    }
    val guideColor = MaterialTheme.colorScheme.outlineVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .drawBehind {
                if (displayDepth <= 0) return@drawBehind
                val unit = IndentWidth.toPx()
                val stroke = 1.dp.toPx()

                for (localIndex in 0 until displayDepth - 1) {
                    val guideIndex = node.depth - displayDepth + localIndex + 1
                    if (node.guides.getOrNull(guideIndex) == true) {
                        val gx = unit * localIndex + unit / 2
                        drawLine(guideColor, Offset(gx, 0f), Offset(gx, size.height), stroke)
                    }
                }

                val x = unit * (displayDepth - 1) + unit / 2
                val midY = size.height / 2
                val endX = x + unit / 2 - stroke / 2f
                if (node.isLastChild) {
                    val r = unit * 0.4f
                    val path = Path().apply {
                        moveTo(x, 0f)
                        lineTo(x, midY - r)
                        quadraticBezierTo(x, midY, x + r, midY)
                        lineTo(endX, midY)
                    }
                    drawPath(
                        path,
                        guideColor,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                } else {
                    drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), stroke)
                    drawLine(guideColor, Offset(x, midY), Offset(endX, midY), stroke, cap = StrokeCap.Round)
                }
            }
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (displayDepth > 0) Spacer(Modifier.width(IndentWidth * displayDepth))

        if (entry.isContainer) {
            ExpandChevron(
                expanded = node.expanded,
                label = stringResource(
                    if (node.expanded) R.string.collapse else R.string.expand,
                ),
                animate = true,
            )
        } else {
            Spacer(Modifier.width(expandSlotWidth()))
        }

        val mediaTopModifier = if (wantsThumbnail) {
            Modifier.align(Alignment.Top).padding(top = 4.dp, end = 6.dp)
        } else {
            Modifier.padding(end = 6.dp)
        }
        Box(mediaTopModifier, contentAlignment = Alignment.Center) {
            if (entry.kind == EntryKind.APP) {
                AsyncImage(
                    model = AppIcon(entry.path),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else if (wantsThumbnail) {
                EntryThumbnail(entry, display)
            } else {
                EntryIcon(
                    entry,
                    tint = if (entry.isContainer) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isVolume) 28.dp else 24.dp),
                    expanded = node.expanded,
                )
            }
        }

        val nameMaxLines = when (display.filenameMode) {
            FilenameDisplayMode.SINGLE_LINE -> 1
            FilenameDisplayMode.TWO_LINES -> 2
            FilenameDisplayMode.THREE_LINES -> 3
            FilenameDisplayMode.FULL -> Int.MAX_VALUE
        }
        val nameOverflow = if (display.filenameMode == FilenameDisplayMode.FULL) {
            TextOverflow.Clip
        } else {
            TextOverflow.Ellipsis
        }

        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .weight(1f)
                .then(if (wantsThumbnail) Modifier.align(Alignment.Top) else Modifier)
                .padding(vertical = 4.dp),
        ) {
            Text(
                entry.name,
                style = if (isVolume) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.bodyMedium,
                fontWeight = when {
                    isVolume -> FontWeight.SemiBold
                    entry.isContainer -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                maxLines = nameMaxLines,
                overflow = nameOverflow,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
                color = if (node.error != null) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            val details = node.error ?: entryDetails(node)
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (node.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isVolume && entry.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, end = 8.dp)
                        .height(3.dp),
                )
            }
        }

        if (node.loading) {
            LoadingIndicator(modifier = Modifier.size(26.dp))
        }

        if (selectable) {
            val icon = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked
            val description = stringResource(if (selected) R.string.deselect else R.string.select)
            val tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(SelectionWidth)
                    .heightIn(min = rowMinHeight)
                    .clickable(enabled = enabled, onClick = onToggleSelect),
            ) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun expandSlotWidth() = IndentWidth + with(LocalDensity.current) { 1.toDp() }

@Composable
private fun ExpandChevron(
    expanded: Boolean,
    label: String?,
    animate: Boolean,
) {
    val target = if (expanded) 90f else 0f
    val animated by animateFloatAsState(target, label = "chevron")
    val rotation = if (animate) animated else target
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val lead = with(LocalDensity.current) { 1.toDp() }
    Canvas(
        Modifier
            .padding(start = lead)
            .size(IndentWidth)
            .rotate(rotation)
            .semantics {
                if (label != null) {
                    contentDescription = label
                }
            },
    ) {
        val stroke = 1.25.dp.toPx()
        val pad = stroke / 2f + 0.5.dp.toPx()
        val half = size.minDimension / 2f - pad
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = Path().apply {
            moveTo(cx - half / 2f, cy - half)
            lineTo(cx + half / 2f, cy)
            lineTo(cx - half / 2f, cy + half)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun StartupEntryRow(
    node: TreeNode,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    selectable: Boolean,
    isVolume: Boolean,
    display: BrowserDisplayConfig,
    wantsThumbnail: Boolean,
    displayDepth: Int,
    rowMinHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val entry = node.entry
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        focused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (displayDepth > 0) Spacer(Modifier.width(IndentWidth * displayDepth))
        if (entry.isContainer) {
            ExpandChevron(
                expanded = node.expanded,
                label = null,
                animate = false,
            )
        } else {
            Spacer(Modifier.width(expandSlotWidth()))
        }
        Box(
            modifier = if (wantsThumbnail) {
                Modifier
                    .align(Alignment.Top)
                    .padding(top = 4.dp, end = 6.dp)
                    .width(display.thumbnailSize.widthDp.dp)
                    .height(display.thumbnailSize.heightDp.dp)
            } else {
                Modifier.padding(end = 6.dp)
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                EntryIcons.forEntry(entry, expanded = node.expanded),
                contentDescription = null,
                tint = if (entry.isContainer) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(
                    when {
                        entry.kind == EntryKind.APP -> 32.dp
                        isVolume -> 28.dp
                        else -> 24.dp
                    },
                ),
            )
        }
        val nameMaxLines = when (display.filenameMode) {
            FilenameDisplayMode.SINGLE_LINE -> 1
            FilenameDisplayMode.TWO_LINES -> 2
            FilenameDisplayMode.THREE_LINES -> 3
            FilenameDisplayMode.FULL -> Int.MAX_VALUE
        }
        val nameOverflow = if (display.filenameMode == FilenameDisplayMode.FULL) {
            TextOverflow.Clip
        } else {
            TextOverflow.Ellipsis
        }
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .weight(1f)
                .then(if (wantsThumbnail) Modifier.align(Alignment.Top) else Modifier)
                .padding(vertical = 4.dp),
        ) {
            Text(
                entry.name,
                style = if (isVolume) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.bodyMedium,
                fontWeight = when {
                    isVolume -> FontWeight.SemiBold
                    entry.isContainer -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                maxLines = nameMaxLines,
                overflow = nameOverflow,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            val details = entryDetails(node, loadFolderCount = false)
            if (details.isNotEmpty()) {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selectable) {
            Box(
                Modifier
                    .width(SelectionWidth)
                    .heightIn(min = rowMinHeight),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun EntryThumbnail(entry: XEntry, display: BrowserDisplayConfig) {
    val isVideo = FileTypes.categoryOf(entry.name, entry.mime) == FileCategory.VIDEO
    var loaded by remember(entry.id, entry.mtime, entry.size) { mutableStateOf(false) }
    val width = display.thumbnailSize.widthDp.dp
    val height = display.thumbnailSize.heightDp.dp
    val playBadge = if (display.thumbnailSize.widthDp >= 80) 20.dp else 16.dp
    val playIcon = if (display.thumbnailSize.widthDp >= 80) 15.dp else 12.dp

    Box(
        modifier = Modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Icon(
                EntryIcons.forEntry(entry),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        AsyncImage(
            model = when {
                entry.scheme == XId.SCHEME_SMB && isVideo -> RemoteVideoThumb(entry)
                entry.scheme == XId.SCHEME_SMB -> RemoteFile(entry)
                isVideo -> VideoThumb(
                    path = entry.localPath ?: entry.path,
                    mtime = entry.mtime,
                    size = entry.size,
                    privileged = entry.localPath == null,
                )
                entry.localPath != null -> File(entry.localPath)
                else -> PrivFile(entry.path, entry.mtime, entry.size)
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { loaded = it is AsyncImagePainter.State.Success },
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(8.dp)),
        )
        if (isVideo && loaded) {
            Box(
                Modifier
                    .size(playBadge)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(playIcon),
                )
            }
        }
    }
}

@Composable
private fun entryDetails(node: TreeNode, loadFolderCount: Boolean = true): String {
    val entry = node.entry
    return when {
        entry.badge != null -> entry.badge
        !entry.isDir && entry.size >= 0 -> {
            val date = Format.dateTime(entry.mtime)
            if (date.isEmpty()) Format.bytes(entry.size) else "${Format.bytes(entry.size)} · $date"
        }
        entry.isDir && loadFolderCount -> {
            rememberFolderFileCount(entry)?.let { "$it ファイル" }
                ?: if (entry.childCountHint >= 0) {
                    pluralStringResource(
                        R.plurals.item_count_plural,
                        entry.childCountHint,
                        entry.childCountHint,
                    )
                } else ""
        }
        entry.isDir && entry.childCountHint >= 0 -> pluralStringResource(
            R.plurals.item_count_plural,
            entry.childCountHint,
            entry.childCountHint,
        )
        else -> ""
    }
}

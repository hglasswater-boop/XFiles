package app.local1st.files.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.local1st.files.R
import app.local1st.files.core.fs.XEntry
import app.local1st.files.core.fs.XId
import app.local1st.files.core.fs.priv.PrivilegedAccess
import app.local1st.files.core.prefs.VideoPlayerSettings
import app.local1st.files.core.prefs.VideoResumeStore
import app.local1st.files.core.util.FileCategory
import app.local1st.files.core.util.FileTypes
import app.local1st.files.ui.components.TooltipIconButton
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * media3 playback: PlayerView for video, an Expressive card UI for audio.
 * Local files use Media3's normal file source; root:// uses a binder-opened seekable fd;
 * smb:// uses SMBJ offset reads so large remote media can stream and seek without a local copy.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaViewer(entry: XEntry, playlist: List<XEntry>, onClose: () -> Unit) {
    val privilegedFdAvailable = PrivilegedAccess.canOpenFd()
    val playable = remember(entry.id, playlist, privilegedFdAvailable) {
        playlist.ifEmpty { listOf(entry) }.filter {
            mediaUri(it) != null || (it.scheme == XId.SCHEME_ROOT && privilegedFdAvailable)
        }
    }
    if (playable.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.cannot_play, entry.name), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.only_local_files_playable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.close))
            }
        }
        return
    }

    val context = LocalContext.current
    val startIndex = remember(playable, entry.id) {
        playable.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    }
    var currentIndex by remember { mutableIntStateOf(startIndex) }
    var rebuildPlayWhenReady by remember { mutableStateOf(true) }
    val bufferPreset by VideoPlayerSettings.bufferPreset(context).collectAsState()
    val player = remember(playable, bufferPreset) {
        val dataSourceFactory = DefaultDataSource.Factory(context, XFilesRemoteDataSource.Factory())
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferPreset.minBufferMs,
                bufferPreset.maxBufferMs,
                bufferPreset.bufferForPlaybackMs,
                bufferPreset.bufferForPlaybackAfterRebufferMs,
            )
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
            .apply {
                setMediaItems(
                    playable.map {
                        val uri = mediaUri(it)
                            ?: Uri.Builder().scheme(XId.SCHEME_ROOT).path(it.path).build()
                        MediaItem.fromUri(uri)
                    },
                    currentIndex.coerceIn(0, playable.lastIndex),
                    C.TIME_UNSET,
                )
                prepare()
                playWhenReady = rebuildPlayWhenReady
            }
    }

    var playing by remember { mutableStateOf(false) }
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var hasPrevious by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                currentIndex = p.currentMediaItemIndex
                playing = p.isPlaying
                metadata = p.mediaMetadata
                hasPrevious = p.hasPreviousMediaItem()
                hasNext = p.hasNextMediaItem()

                if (p.playbackState == Player.STATE_ENDED) {
                    playable.getOrNull(p.currentMediaItemIndex)
                        ?.takeIf(::isVideoEntry)
                        ?.let { VideoResumeStore.clear(context, it.id) }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            saveCurrentVideoResume(context, playable, player)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, currentIndex) {
        val resumeEntry = playable.getOrNull(currentIndex) ?: return@LaunchedEffect
        if (!isVideoEntry(resumeEntry)) return@LaunchedEffect

        val resumeMs = VideoResumeStore.load(context, resumeEntry.id)
        if (resumeMs > player.currentPosition + VIDEO_RESUME_RESTORE_TOLERANCE_MS) {
            player.seekTo(resumeMs)
        }

        while (isActive) {
            delay(VIDEO_RESUME_SAVE_INTERVAL_MS)
            if (player.currentMediaItemIndex != currentIndex) break
            saveVideoResume(context, resumeEntry, player)
        }
    }

    val currentEntry = playable[currentIndex.coerceIn(0, playable.lastIndex)]
    val isVideo = isVideoEntry(currentEntry)

    if (isVideo) {
        VideoCompatibilityGuard(
            player = player,
            entry = currentEntry,
            onClose = onClose,
        ) {
            TvVideoPlayer(
                player = player,
                entry = currentEntry,
                playing = playing,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                bufferPreset = bufferPreset,
                onBufferPresetChanged = { preset ->
                    if (preset == bufferPreset) return@TvVideoPlayer
                    saveVideoResume(context, currentEntry, player)
                    rebuildPlayWhenReady = player.playWhenReady
                    VideoPlayerSettings.setBufferPreset(context, preset)
                },
                onClose = onClose,
            )
        }
    } else {
        AudioPlayerScreen(
            player = player,
            entry = currentEntry,
            metadata = metadata,
            playing = playing,
            trackNumber = currentIndex + 1,
            trackCount = playable.size,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onClose = onClose,
        )
    }
}

@Composable
private fun TvVideoPlayer(
    player: ExoPlayer,
    entry: XEntry,
    playing: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    bufferPreset: VideoPlayerSettings.BufferPreset,
    onBufferPresetChanged: (VideoPlayerSettings.BufferPreset) -> Unit,
    onClose: () -> Unit,
) {
    val settingsFocusRequester = remember { FocusRequester() }
    var settingsFocused by remember(entry.id) { mutableStateOf(false) }
    var settingsOpen by remember(entry.id) { mutableStateOf(false) }
    var requestSettingsFocus by remember(entry.id) { mutableStateOf(false) }

    LaunchedEffect(requestSettingsFocus) {
        if (!requestSettingsFocus) return@LaunchedEffect
        repeat(TV_SETTINGS_FOCUS_RETRY_FRAMES) {
            withFrameNanos { }
            if (runCatching { settingsFocusRequester.requestFocus() }.getOrDefault(false)) {
                requestSettingsFocus = false
                return@LaunchedEffect
            }
        }
        requestSettingsFocus = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || settingsFocused || settingsOpen) {
                    false
                } else if (event.key == Key.DirectionUp) {
                    // Let VideoPlayerScreen reveal its chrome, then move focus to the TV settings
                    // button on the next frame so the remote can actually reach the top-right action.
                    requestSettingsFocus = true
                    false
                } else {
                    false
                }
            },
    ) {
        VideoPlayerScreen(
            player = player,
            entry = entry,
            playing = playing,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onClose = onClose,
            tvRemoteControls = true,
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        ) {
            FilledIconButton(
                onClick = { settingsOpen = true },
                modifier = Modifier
                    .focusRequester(settingsFocusRequester)
                    .onFocusChanged { settingsFocused = it.isFocused },
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "プレイヤー設定",
                )
            }
            DropdownMenu(
                expanded = settingsOpen,
                onDismissRequest = { settingsOpen = false },
            ) {
                Text(
                    "再生バッファ",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                BufferPresetMenuItem(
                    label = "標準（最大50秒）",
                    selected = bufferPreset == VideoPlayerSettings.BufferPreset.STANDARD,
                    onClick = {
                        settingsOpen = false
                        onBufferPresetChanged(VideoPlayerSettings.BufferPreset.STANDARD)
                    },
                )
                BufferPresetMenuItem(
                    label = "厚め（最大2分）",
                    selected = bufferPreset == VideoPlayerSettings.BufferPreset.THICK,
                    onClick = {
                        settingsOpen = false
                        onBufferPresetChanged(VideoPlayerSettings.BufferPreset.THICK)
                    },
                )
                BufferPresetMenuItem(
                    label = "最大（最大3分）",
                    selected = bufferPreset == VideoPlayerSettings.BufferPreset.MAXIMUM,
                    onClick = {
                        settingsOpen = false
                        onBufferPresetChanged(VideoPlayerSettings.BufferPreset.MAXIMUM)
                    },
                )
            }
        }
    }
}

@Composable
private fun BufferPresetMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(if (selected) "✓  $label" else "　 $label") },
        onClick = onClick,
    )
}

private fun mediaUri(entry: XEntry) = when {
    entry.localPath != null -> File(entry.localPath).toUri()
    entry.scheme == "content" -> entry.id.toUri()
    entry.scheme == XId.SCHEME_SMB -> entry.id.toUri()
    else -> null
}

private fun isVideoEntry(entry: XEntry): Boolean =
    FileTypes.categoryOf(entry.name, entry.mime) == FileCategory.VIDEO

private fun saveCurrentVideoResume(context: Context, playable: List<XEntry>, player: Player) {
    val currentEntry = playable.getOrNull(player.currentMediaItemIndex) ?: return
    if (isVideoEntry(currentEntry)) saveVideoResume(context, currentEntry, player)
}

private fun saveVideoResume(context: Context, entry: XEntry, player: Player) {
    val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    VideoResumeStore.save(
        context = context,
        mediaId = entry.id,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = durationMs,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudioPlayerScreen(
    player: Player,
    entry: XEntry,
    metadata: MediaMetadata,
    playing: Boolean,
    trackNumber: Int,
    trackCount: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onClose: () -> Unit,
) {
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            delay(200L)
        }
    }

    // Nothing scrolls here, so the bar stays put; only the background reaches into the system bars.
    ViewerChrome(
        collapsible = false,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.music)) },
                navigationIcon = {
                    TooltipIconButton(stringResource(R.string.close), Icons.Outlined.Close, onClick = onClose)
                },
            )
        },
    ) { chrome ->
        Box(Modifier.fillMaxSize().padding(chrome), contentAlignment = Alignment.Center) {
            ElevatedCard(Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp),
                    )
                    Text(
                        metadata.title?.toString()?.takeIf { it.isNotBlank() }
                            ?: entry.name.substringBeforeLast('.'),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_artist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${entry.name}  ·  $trackNumber/$trackCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    LinearWavyProgressIndicator(
                        progress = {
                            if (durationMs > 0) {
                                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(formatPlayTime(positionMs), style = MaterialTheme.typography.labelMedium)
                        Text(formatPlayTime(durationMs), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TooltipIconButton(
                            stringResource(R.string.previous),
                            Icons.Outlined.SkipPrevious,
                            enabled = hasPrevious,
                            onClick = { player.seekToPreviousMediaItem() },
                        )
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(stringResource(if (playing) R.string.pause else R.string.play)) } },
                            state = rememberTooltipState(),
                        ) {
                            FilledIconButton(
                                onClick = { if (playing) player.pause() else player.play() },
                                modifier = Modifier.size(64.dp),
                            ) {
                                Icon(
                                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                                )
                            }
                        }
                        TooltipIconButton(
                            stringResource(R.string.next),
                            Icons.Outlined.SkipNext,
                            enabled = hasNext,
                            onClick = { player.seekToNextMediaItem() },
                        )
                    }
                }
            }
        }
    }
}

internal fun formatPlayTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private const val VIDEO_RESUME_SAVE_INTERVAL_MS = 2_000L
private const val VIDEO_RESUME_RESTORE_TOLERANCE_MS = 2_000L
private const val TV_SETTINGS_FOCUS_RETRY_FRAMES = 6

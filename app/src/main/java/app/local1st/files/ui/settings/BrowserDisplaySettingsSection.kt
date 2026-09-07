package app.local1st.files.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.local1st.files.core.prefs.BrowserDisplayPreset
import app.local1st.files.core.prefs.BrowserDisplaySettings
import app.local1st.files.core.prefs.FilenameDisplayMode
import app.local1st.files.core.prefs.ThumbnailSize
import app.local1st.files.core.prefs.VideoStoryboardSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BrowserDisplaySettingsSection() {
    val context = LocalContext.current
    val config by BrowserDisplaySettings.state(context).collectAsState()
    val storyboardSampleCount by VideoStoryboardSettings.state(context).collectAsState()
    val storyboardMinSpacingSeconds by VideoStoryboardSettings.spacingState(context).collectAsState()
    val preset = BrowserDisplayPreset.matching(config)

    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(
                "一覧表示",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                if (preset == null) "プリセット: カスタム" else "プリセット: ${preset.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

            DisplayRadioRow(
                title = "プリセット",
                options = BrowserDisplayPreset.entries.map { it to it.label() },
                selected = preset,
                onSelect = { BrowserDisplaySettings.applyPreset(context, it) },
            )
            DisplayRadioRow(
                title = "サムネイルサイズ",
                options = listOf(
                    ThumbnailSize.SMALL to "小",
                    ThumbnailSize.MEDIUM to "中",
                    ThumbnailSize.LARGE to "大",
                    ThumbnailSize.EXTRA_LARGE to "特大",
                ),
                selected = config.thumbnailSize,
                onSelect = { BrowserDisplaySettings.setThumbnailSize(context, it) },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "動画プレビュー枚数",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${storyboardSampleCount}枚",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = storyboardSampleCount.toFloat(),
                    onValueChange = { value ->
                        VideoStoryboardSettings.setSampleCount(context, value.roundToInt())
                    },
                    valueRange = VideoStoryboardSettings.MIN_SAMPLE_COUNT.toFloat()..
                        VideoStoryboardSettings.MAX_SAMPLE_COUNT.toFloat(),
                    steps = (
                        (VideoStoryboardSettings.MAX_SAMPLE_COUNT - VideoStoryboardSettings.MIN_SAMPLE_COUNT) /
                            VideoStoryboardSettings.SAMPLE_COUNT_STEP
                        ) - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${VideoStoryboardSettings.MIN_SAMPLE_COUNT}〜${VideoStoryboardSettings.MAX_SAMPLE_COUNT}枚を2枚刻みで指定します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(
                        "動画プレビュー最低間隔",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${storyboardMinSpacingSeconds}秒",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = storyboardMinSpacingSeconds.toFloat(),
                    onValueChange = { value ->
                        VideoStoryboardSettings.setMinSpacingSeconds(context, value.roundToInt())
                    },
                    valueRange = VideoStoryboardSettings.MIN_SPACING_SECONDS.toFloat()..
                        VideoStoryboardSettings.MAX_SPACING_SECONDS.toFloat(),
                    steps = VideoStoryboardSettings.MAX_SPACING_SECONDS -
                        VideoStoryboardSettings.MIN_SPACING_SECONDS - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "1〜10秒を1秒刻みで指定します。標準は5秒です。指定枚数より密になる場合は、最低間隔を優先して実際のプレビュー枚数を自動で減らします。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DisplayRadioRow(
                title = "ファイル名",
                options = listOf(
                    FilenameDisplayMode.SINGLE_LINE to "1行",
                    FilenameDisplayMode.TWO_LINES to "2行",
                    FilenameDisplayMode.THREE_LINES to "3行",
                    FilenameDisplayMode.FULL to "全文",
                ),
                selected = config.filenameMode,
                onSelect = { BrowserDisplaySettings.setFilenameMode(context, it) },
            )
            DisplayRadioRow(
                title = "表示する階層数",
                options = (0..4).map { it to it.toString() },
                selected = config.treeLevels,
                onSelect = { BrowserDisplaySettings.setTreeLevels(context, it) },
            )
            Text(
                "階層数は見た目のインデントだけを制限します。フォルダ構造や展開状態は変わりません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }

    SettingsBackupSection()
}

private fun BrowserDisplayPreset.label(): String = when (this) {
    BrowserDisplayPreset.COMPACT -> "コンパクト"
    BrowserDisplayPreset.STANDARD -> "標準"
    BrowserDisplayPreset.MEDIA -> "メディア"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> DisplayRadioRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEach { (value, label) ->
                val checked = value == selected
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .selectable(
                            selected = checked,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        )
                        .padding(end = 14.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = checked, onClick = null)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

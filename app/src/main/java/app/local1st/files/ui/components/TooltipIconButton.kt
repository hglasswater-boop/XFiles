package app.local1st.files.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.local1st.files.BuildConfig
import app.local1st.files.R

/**
 * An icon-only button that reveals its [label] on long-press (and on hover), so every
 * icon is self-explanatory. The label doubles as the accessibility description.
 *
 * Pass [selected] for a button that toggles something on and off and has no second icon to say so:
 * it takes the accent colour while the thing is on. The [label] still names what a tap would do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val isTvEdition = BuildConfig.APPLICATION_ID.endsWith(".tv")
    val skipTvBackFocus = isTvEdition && label == stringResource(R.string.back)
    var focused by remember { mutableStateOf(false) }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (skipTvBackFocus) {
                        Modifier.focusProperties { canFocus = false }
                    } else {
                        Modifier
                    },
                ),
            colors = when {
                isTvEdition && focused -> IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                selected -> IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                )
                else -> IconButtonDefaults.iconButtonColors()
            },
        ) {
            Icon(icon, contentDescription = label)
        }
    }
}
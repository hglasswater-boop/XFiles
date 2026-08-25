package app.local1st.files

import androidx.compose.runtime.Composable

/** Mobile edition does not use the GitHub self-updater. */
@Composable
fun EditionStartupUpdateCheck() = Unit

/** TV-only update UI hook; intentionally empty in the mobile edition. */
@Composable
fun EditionUpdateSettingsSection() = Unit

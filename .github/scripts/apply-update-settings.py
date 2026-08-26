from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)


def patch_updater(path: str, prefix: str, obj: str, release_type: str) -> None:
    p = Path(path)
    text = p.read_text()

    text = replace_once(
        text,
        "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.height\n",
        "import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.padding\n",
        f"{prefix} layout imports",
    )
    text = replace_once(
        text,
        "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n",
        "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Card\nimport androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.OutlinedButton\nimport androidx.compose.material3.Switch\nimport androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n",
        f"{prefix} material imports",
    )
    text = replace_once(
        text,
        "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\n",
        "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.platform.LocalContext\n",
        f"{prefix} alignment import",
    )
    text = replace_once(
        text,
        "import java.net.HttpURLConnection\nimport java.net.URL\n",
        "import java.net.HttpURLConnection\nimport java.net.URL\nimport java.text.DateFormat\nimport java.util.Date\n",
        f"{prefix} date imports",
    )

    text = replace_once(
        text,
        '    private const val LAST_AUTO_CHECK = "last_auto_check"\n    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L\n',
        '    private const val LAST_AUTO_CHECK = "last_auto_check"\n    private const val AUTO_CHECK_ENABLED = "auto_check_enabled"\n    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L\n',
        f"{prefix} pref constant",
    )
    old_methods = '''    fun autoCheckDue(context: Context): Boolean {\n        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n            .getLong(LAST_AUTO_CHECK, 0L)\n        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS\n    }\n\n    fun markAutoCheck(context: Context) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n            .edit()\n            .putLong(LAST_AUTO_CHECK, System.currentTimeMillis())\n            .apply()\n    }\n'''
    new_methods = '''    fun isAutoCheckEnabled(context: Context): Boolean =\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n            .getBoolean(AUTO_CHECK_ENABLED, true)\n\n    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n            .edit()\n            .putBoolean(AUTO_CHECK_ENABLED, enabled)\n            .apply()\n    }\n\n    fun lastCheck(context: Context): Long =\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n            .getLong(LAST_AUTO_CHECK, 0L)\n\n    fun autoCheckDue(context: Context): Boolean {\n        if (!isAutoCheckEnabled(context)) return false\n        return System.currentTimeMillis() - lastCheck(context) >= AUTO_CHECK_INTERVAL_MS\n    }\n\n    fun markChecked(context: Context): Long {\n        val now = System.currentTimeMillis()\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n            .edit()\n            .putLong(LAST_AUTO_CHECK, now)\n            .apply()\n        return now\n    }\n'''
    text = replace_once(text, old_methods, new_methods, f"{prefix} pref methods")
    text = text.replace(f"{obj}.markAutoCheck(context)", f"{obj}.markChecked(context)")

    startup_anchor = "@Composable\nfun EditionStartupUpdateCheck() {"
    settings_block = f'''@Composable\nfun EditionUpdateSettingsSection() {{\n    val context = LocalContext.current\n    val scope = rememberCoroutineScope()\n    var autoCheckEnabled by remember {{ mutableStateOf({obj}.isAutoCheckEnabled(context)) }}\n    var lastCheck by remember {{ mutableStateOf({obj}.lastCheck(context)) }}\n    var checking by remember {{ mutableStateOf(false) }}\n    var statusMessage by remember {{ mutableStateOf<String?>(null) }}\n    var release by remember {{ mutableStateOf<{release_type}?>(null) }}\n\n    Card(Modifier.fillMaxWidth()) {{\n        Column(Modifier.fillMaxWidth().padding(16.dp)) {{\n            Text(\n                stringResource(\n                    R.string.update_current_version,\n                    BuildConfig.VERSION_NAME,\n                    BuildConfig.VERSION_CODE,\n                ),\n                style = MaterialTheme.typography.bodyMedium,\n            )\n            Spacer(Modifier.height(12.dp))\n            Row(\n                modifier = Modifier.fillMaxWidth(),\n                verticalAlignment = Alignment.CenterVertically,\n                horizontalArrangement = Arrangement.spacedBy(12.dp),\n            ) {{\n                Column(Modifier.weight(1f)) {{\n                    Text(\n                        stringResource(R.string.update_auto_check),\n                        style = MaterialTheme.typography.bodyLarge,\n                    )\n                    Text(\n                        stringResource(R.string.update_auto_check_summary),\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    )\n                }}\n                Switch(\n                    checked = autoCheckEnabled,\n                    onCheckedChange = {{ enabled ->\n                        autoCheckEnabled = enabled\n                        {obj}.setAutoCheckEnabled(context, enabled)\n                    }},\n                )\n            }}\n            Spacer(Modifier.height(12.dp))\n            Text(\n                if (lastCheck > 0L) {{\n                    stringResource(\n                        R.string.update_last_checked,\n                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)\n                            .format(Date(lastCheck)),\n                    )\n                }} else {{\n                    stringResource(R.string.update_never_checked)\n                }},\n                style = MaterialTheme.typography.bodySmall,\n                color = MaterialTheme.colorScheme.onSurfaceVariant,\n            )\n            statusMessage?.let {{ message ->\n                Spacer(Modifier.height(6.dp))\n                Text(\n                    message,\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                )\n            }}\n            Spacer(Modifier.height(12.dp))\n            OutlinedButton(\n                enabled = !checking,\n                onClick = {{\n                    scope.launch {{\n                        checking = true\n                        statusMessage = null\n                        runCatching {{ {obj}.check() }}\n                            .onSuccess {{ found ->\n                                lastCheck = {obj}.markChecked(context)\n                                if (found == null) {{\n                                    statusMessage = context.getString(R.string.update_up_to_date)\n                                }} else {{\n                                    release = found\n                                }}\n                            }}\n                            .onFailure {{ error ->\n                                statusMessage = context.getString(\n                                    R.string.update_check_failed,\n                                    error.message ?: error.javaClass.simpleName,\n                                )\n                            }}\n                        checking = false\n                    }}\n                }},\n            ) {{\n                Text(\n                    stringResource(\n                        if (checking) R.string.update_checking\n                        else R.string.update_check_now,\n                    ),\n                )\n            }}\n        }}\n    }}\n\n    release?.let {{ available ->\n        EditionSettingsUpdateDialog(\n            available = available,\n            onDismiss = {{ release = null }},\n        )\n    }}\n}}\n\n@Composable\nprivate fun EditionSettingsUpdateDialog(\n    available: {release_type},\n    onDismiss: () -> Unit,\n) {{\n    val context = LocalContext.current\n    val scope = rememberCoroutineScope()\n    var downloading by remember {{ mutableStateOf(false) }}\n    var errorMessage by remember {{ mutableStateOf<String?>(null) }}\n\n    AlertDialog(\n        onDismissRequest = {{ if (!downloading) onDismiss() }},\n        title = {{ Text(stringResource(R.string.{prefix}_update_available_title)) }},\n        text = {{\n            Column {{\n                Text(\n                    stringResource(\n                        R.string.{prefix}_update_available_message,\n                        available.versionName,\n                        available.buildNumber,\n                    ),\n                )\n                if (!{obj}.canInstallPackages(context)) {{\n                    Spacer(Modifier.height(8.dp))\n                    Text(\n                        stringResource(R.string.{prefix}_update_install_permission_hint),\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n                    )\n                }}\n                errorMessage?.let {{\n                    Spacer(Modifier.height(8.dp))\n                    Text(\n                        stringResource(R.string.{prefix}_update_error, it),\n                        color = MaterialTheme.colorScheme.error,\n                    )\n                }}\n            }}\n        }},\n        confirmButton = {{\n            TextButton(\n                enabled = !downloading,\n                onClick = {{\n                    if (!{obj}.canInstallPackages(context)) {{\n                        {obj}.openInstallPermission(context)\n                        return@TextButton\n                    }}\n                    scope.launch {{\n                        downloading = true\n                        errorMessage = null\n                        runCatching {{ {obj}.downloadAndValidate(context, available) }}\n                            .onSuccess {{ {obj}.launchInstaller(context, it) }}\n                            .onFailure {{ errorMessage = it.message ?: it.javaClass.simpleName }}\n                        downloading = false\n                    }}\n                }},\n            ) {{\n                Text(\n                    stringResource(\n                        if (downloading) R.string.{prefix}_update_downloading\n                        else R.string.{prefix}_update_install,\n                    ),\n                )\n            }}\n        }},\n        dismissButton = {{\n            TextButton(enabled = !downloading, onClick = onDismiss) {{\n                Text(stringResource(R.string.{prefix}_update_later))\n            }}\n        }},\n    )\n}}\n\n'''
    text = replace_once(text, startup_anchor, settings_block + startup_anchor, f"{prefix} settings UI")
    p.write_text(text)


patch_updater(
    "app/src/mobile/java/app/local1st/files/EditionUpdater.kt",
    "mobile",
    "MobileSelfUpdater",
    "MobileRelease",
)
patch_updater(
    "app/src/tv/java/app/local1st/files/EditionUpdater.kt",
    "tv",
    "TvSelfUpdater",
    "TvRelease",
)

settings = Path("app/src/main/java/app/local1st/files/ui/settings/SettingsScreen.kt")
text = settings.read_text()
text = replace_once(
    text,
    "import app.local1st.files.BuildConfig\nimport app.local1st.files.R\n",
    "import app.local1st.files.BuildConfig\nimport app.local1st.files.EditionUpdateSettingsSection\nimport app.local1st.files.R\n",
    "settings updater import",
)
text = replace_once(
    text,
    "                SectionHeader(stringResource(R.string.about))\n",
    "                SectionHeader(stringResource(R.string.update_settings_title))\n                EditionUpdateSettingsSection()\n\n                SectionHeader(stringResource(R.string.about))\n",
    "settings update section",
)
settings.write_text(text)

english_extra = '''\n    <string name="update_settings_title">Updates</string>\n    <string name="update_current_version">Current version: %1$s (%2$d)</string>\n    <string name="update_auto_check">Automatically check for updates</string>\n    <string name="update_auto_check_summary">Check GitHub at most once every 24 hours when XFiles starts.</string>\n    <string name="update_last_checked">Last checked: %1$s</string>\n    <string name="update_never_checked">Last checked: never</string>\n    <string name="update_check_now">Check now</string>\n    <string name="update_checking">Checking…</string>\n    <string name="update_up_to_date">You already have the latest build.</string>\n    <string name="update_check_failed">Update check failed: %1$s</string>\n'''
japanese_extra = '''\n    <string name="update_settings_title">アップデート</string>\n    <string name="update_current_version">現在のバージョン: %1$s (%2$d)</string>\n    <string name="update_auto_check">アップデートを自動確認</string>\n    <string name="update_auto_check_summary">XFiles 起動時、24時間に1回まで GitHub の最新版を確認します。</string>\n    <string name="update_last_checked">最終確認: %1$s</string>\n    <string name="update_never_checked">最終確認: まだ確認していません</string>\n    <string name="update_check_now">今すぐ確認</string>\n    <string name="update_checking">確認中…</string>\n    <string name="update_up_to_date">現在のビルドが最新です。</string>\n    <string name="update_check_failed">更新確認に失敗しました: %1$s</string>\n'''

for path, extra in [
    ("app/src/mobile/res/values/strings.xml", english_extra),
    ("app/src/tv/res/values/strings.xml", english_extra),
    ("app/src/mobile/res/values-ja/strings.xml", japanese_extra),
    ("app/src/tv/res/values-ja/strings.xml", japanese_extra),
]:
    p = Path(path)
    text = p.read_text()
    if "update_settings_title" not in text:
        text = replace_once(text, "</resources>", extra + "</resources>", path)
        p.write_text(text)

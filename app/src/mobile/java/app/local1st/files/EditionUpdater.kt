package app.local1st.files

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.local1st.files.core.update.ReleaseAsset
import app.local1st.files.core.update.ResolvedUpdateRelease
import app.local1st.files.core.update.SelfUpdateSource
import app.local1st.files.core.update.StableReleaseMetadata
import app.local1st.files.core.update.SelfUpdateEdition
import app.local1st.files.core.update.SelfUpdateReleaseContract
import app.local1st.files.core.util.SelfUpdateApkVerifier
import app.local1st.files.core.util.SelfUpdateInstaller
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class MobileReleaseChannel(
    val packageName: String,
) {
    NORMAL("app.local1st.files"),
    DIAGNOSTIC("app.local1st.files.diagnostic"),
}

private data class MobileRelease(
    val channel: MobileReleaseChannel,
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val downloadUrl: String,
    val source: SelfUpdateSource,
)

private data class InstalledNormalPackage(
    val buildNumber: Int,
    val isDebuggable: Boolean?,
)

private object MobileSelfUpdater {
    private const val PREFS = "mobile_self_update"
    private const val LAST_AUTO_CHECK = "last_auto_check"
    private const val AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun isAutoCheckEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(AUTO_CHECK_ENABLED, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTO_CHECK_ENABLED, enabled)
            .apply()
    }

    fun lastCheck(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_AUTO_CHECK, 0L)

    fun autoCheckDue(context: Context): Boolean {
        if (!isAutoCheckEnabled(context)) return false
        return System.currentTimeMillis() - lastCheck(context) >= AUTO_CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context): Long {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_AUTO_CHECK, now)
            .apply()
        return now
    }

    suspend fun checkNormal(context: Context): MobileRelease? = withContext(Dispatchers.IO) {
        val stable = runCatching {
            fetchRelease(SelfUpdateReleaseContract.STABLE_LATEST_API)?.let {
                SelfUpdateReleaseContract.resolveStable(
                    edition = SelfUpdateEdition.MOBILE,
                    release = it.stableReleaseMetadata(),
                )
            }
        }
        val debug = runCatching {
            fetchRelease(SelfUpdateReleaseContract.DEBUG_LATEST_API)?.let {
                SelfUpdateReleaseContract.resolveNormal(
                    edition = SelfUpdateEdition.MOBILE,
                    assets = it.releaseAssets(),
                )
            }
        }
        val installed = installedNormalPackage(context)
        val resolved = SelfUpdateReleaseContract.selectNormalUpdate(
            stable = stable.getOrNull(),
            debug = debug.getOrNull(),
            installedBuild = installed?.buildNumber,
            installedIsDebuggable = installed?.isDebuggable,
        )
        if (resolved == null && (stable.isFailure || debug.isFailure)) {
            throw (stable.exceptionOrNull() ?: debug.exceptionOrNull()!!)
        }
        resolved?.let {
            MobileRelease(
                channel = MobileReleaseChannel.NORMAL,
                versionName = it.versionName,
                buildNumber = it.buildNumber,
                assetName = it.assetName,
                downloadUrl = it.downloadUrl,
                source = it.source,
            )
        }
    }

    suspend fun checkDiagnostic(context: Context): MobileRelease? = withContext(Dispatchers.IO) {
        val json = fetchRelease(SelfUpdateReleaseContract.DIAGNOSTIC_LATEST_API)
            ?: return@withContext null
        val resolved = SelfUpdateReleaseContract.resolveDiagnosticMobile(json.releaseAssets())
        val installedBuild = installedBuildNumber(context, MobileReleaseChannel.DIAGNOSTIC.packageName)
        if (installedBuild != null && !resolved.isNewerThan(installedBuild)) {
            return@withContext null
        }
        MobileRelease(
            channel = MobileReleaseChannel.DIAGNOSTIC,
            versionName = resolved.versionName,
            buildNumber = resolved.buildNumber,
            assetName = resolved.assetName,
            downloadUrl = resolved.downloadUrl,
            source = SelfUpdateSource.DEBUG,
        )
    }

    suspend fun downloadAndValidate(context: Context, release: MobileRelease): File =
        withContext(Dispatchers.IO) {
            val updateDir = File(context.cacheDir, "mobile-updates").apply { mkdirs() }
            val partial = File(updateDir, "${release.assetName}.part")
            val target = File(updateDir, release.assetName)
            partial.delete()
            target.delete()

            val connection = openConnection(release.downloadUrl, "application/octet-stream")
            try {
                val code = connection.responseCode
                if (code !in 200..299) error("Download HTTP $code")
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            validateApk(context, target, release)
            target
        }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchInstaller(context: Context, apk: File) {
        SelfUpdateInstaller.install(context, apk)
    }

    @Suppress("DEPRECATION")
    private fun installedBuildNumber(context: Context, packageName: String): Int? =
        try {
            context.packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    @Suppress("DEPRECATION")
    private fun installedNormalPackage(context: Context): InstalledNormalPackage? =
        try {
            val packageInfo = context.packageManager.getPackageInfo(
                MobileReleaseChannel.NORMAL.packageName,
                0,
            )
            InstalledNormalPackage(
                buildNumber = packageInfo.versionCode,
                isDebuggable = packageInfo.applicationInfo?.let { app ->
                    app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                },
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, apk: File, release: MobileRelease) {
        val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("Downloaded APK could not be read")
        if (packageInfo.packageName != release.channel.packageName) {
            error("Downloaded APK package does not match the selected channel")
        }
        val downloadedBuild = packageInfo.versionCode
        if (downloadedBuild != release.buildNumber) {
            error("Downloaded APK build number does not match the GitHub release")
        }

        if (release.channel == MobileReleaseChannel.NORMAL) {
            val downloadedIsDebuggable = packageInfo.applicationInfo?.let { app ->
                app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            } ?: false
            if (downloadedIsDebuggable != (release.source == SelfUpdateSource.DEBUG)) {
                error("Downloaded APK build type does not match GitHub release")
            }
            val installed = installedNormalPackage(context)
            val candidate = ResolvedUpdateRelease(
                versionName = release.versionName,
                buildNumber = release.buildNumber,
                assetName = release.assetName,
                downloadUrl = release.downloadUrl,
                source = release.source,
            )
            if (!candidate.isInstallableOver(installed?.buildNumber, installed?.isDebuggable)) {
                error("Downloaded APK is not an installable normal update")
            }
        } else {
            val installedBuild = installedBuildNumber(context, release.channel.packageName)
            if (installedBuild != null && downloadedBuild <= installedBuild) {
                error("Downloaded APK is not newer than the installed build")
            }
        }
        SelfUpdateApkVerifier.requireMatchingInstalledSigner(context, apk, release.channel.packageName)
    }

    private fun JSONObject.stableReleaseMetadata(): StableReleaseMetadata =
        StableReleaseMetadata(
            tagName = getString("tag_name"),
            body = optString("body"),
            draft = optBoolean("draft"),
            prerelease = optBoolean("prerelease"),
            assets = releaseAssets(),
        )

    private fun fetchRelease(url: String): JSONObject? {
        val connection = openConnection(url, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (code !in 200..299) error("GitHub HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.releaseAssets(): List<ReleaseAsset> {
        val jsonAssets = getJSONArray("assets")
        return buildList {
            for (index in 0 until jsonAssets.length()) {
                val asset = jsonAssets.getJSONObject(index)
                add(
                    ReleaseAsset(
                        name = asset.getString("name"),
                        downloadUrl = asset.getString("browser_download_url"),
                    ),
                )
            }
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "XFiles/${BuildConfig.VERSION_NAME}")
        }
}

@Composable
fun EditionUpdateSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoCheckEnabled by remember { mutableStateOf(MobileSelfUpdater.isAutoCheckEnabled(context)) }
    var lastCheck by remember { mutableStateOf(MobileSelfUpdater.lastCheck(context)) }
    var checkingNormal by remember { mutableStateOf(false) }
    var checkingDiagnostic by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<MobileRelease?>(null) }

    fun requestNormalInstall() {
        scope.launch {
            checkingNormal = true
            statusMessage = null
            runCatching { MobileSelfUpdater.checkNormal(context) }
                .onSuccess { found ->
                    lastCheck = MobileSelfUpdater.markChecked(context)
                    if (found == null) {
                        statusMessage = context.getString(R.string.update_normal_up_to_date)
                    } else {
                        release = found
                    }
                }
                .onFailure { error ->
                    statusMessage = context.getString(
                        R.string.update_check_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            checkingNormal = false
        }
    }

    fun requestDiagnosticInstall() {
        scope.launch {
            checkingDiagnostic = true
            statusMessage = null
            runCatching { MobileSelfUpdater.checkDiagnostic(context) }
                .onSuccess { found ->
                    if (found == null) {
                        statusMessage = context.getString(R.string.update_diagnostic_up_to_date)
                    } else {
                        release = found
                    }
                }
                .onFailure { error ->
                    statusMessage = context.getString(
                        R.string.update_check_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
            checkingDiagnostic = false
        }
    }

    val checking = checkingNormal || checkingDiagnostic
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(
                    R.string.update_current_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.update_auto_check),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.update_auto_check_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoCheckEnabled,
                    onCheckedChange = { enabled ->
                        autoCheckEnabled = enabled
                        MobileSelfUpdater.setAutoCheckEnabled(context, enabled)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (lastCheck > 0L) {
                    stringResource(
                        R.string.update_last_checked,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(lastCheck)),
                    )
                } else {
                    stringResource(R.string.update_never_checked)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            statusMessage?.let { message ->
                Spacer(Modifier.height(6.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !checking,
                    onClick = ::requestNormalInstall,
                ) {
                    Text(
                        stringResource(
                            if (checkingNormal) R.string.update_checking
                            else R.string.update_install_latest_normal,
                        ),
                    )
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !checking,
                    onClick = ::requestDiagnosticInstall,
                ) {
                    Text(
                        stringResource(
                            if (checkingDiagnostic) {
                                R.string.update_checking
                            } else {
                                R.string.update_install_diagnostic
                            },
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.update_diagnostic_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    release?.let { available ->
        MobileUpdateDialog(
            available = available,
            onDismiss = { release = null },
        )
    }
}

@Composable
private fun MobileUpdateDialog(
    available: MobileRelease,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var preparingInstall by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading && !preparingInstall) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (available.channel == MobileReleaseChannel.NORMAL) {
                        R.string.mobile_update_available_title
                    } else {
                        R.string.mobile_diagnostic_available_title
                    },
                ),
            )
        },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.mobile_update_available_message,
                        available.versionName,
                        available.buildNumber,
                    ),
                )
                if (!MobileSelfUpdater.canInstallPackages(context)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mobile_update_install_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mobile_update_error, it),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading && !preparingInstall,
                onClick = {
                    if (!MobileSelfUpdater.canInstallPackages(context)) {
                        MobileSelfUpdater.openInstallPermission(context)
                        return@TextButton
                    }
                    scope.launch {
                        downloading = true
                        preparingInstall = false
                        errorMessage = null
                        val apk = runCatching {
                            MobileSelfUpdater.downloadAndValidate(context, available)
                        }.getOrElse { error ->
                            downloading = false
                            errorMessage = error.message ?: error.javaClass.simpleName
                            return@launch
                        }
                        downloading = false
                        preparingInstall = true
                        runCatching {
                            withContext(Dispatchers.IO) {
                                MobileSelfUpdater.launchInstaller(context, apk)
                            }
                        }.onSuccess {
                            onDismiss()
                        }.onFailure { error ->
                            preparingInstall = false
                            errorMessage = error.message ?: error.javaClass.simpleName
                        }
                    }
                },
            ) {
                Text(
                    stringResource(
                        when {
                            downloading -> R.string.mobile_update_downloading
                            preparingInstall -> R.string.mobile_update_preparing_install
                            else -> R.string.mobile_update_install
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading && !preparingInstall, onClick = onDismiss) {
                Text(stringResource(R.string.mobile_update_later))
            }
        },
    )
}

@Composable
fun EditionStartupUpdateCheck() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<MobileRelease?>(null) }

    LaunchedEffect(Unit) {
        if (!MobileSelfUpdater.autoCheckDue(context)) return@LaunchedEffect
        runCatching { MobileSelfUpdater.checkNormal(context) }
            .onSuccess {
                MobileSelfUpdater.markChecked(context)
                release = it
            }
    }

    release?.let { available ->
        MobileUpdateDialog(
            available = available,
            onDismiss = { release = null },
        )
    }
}

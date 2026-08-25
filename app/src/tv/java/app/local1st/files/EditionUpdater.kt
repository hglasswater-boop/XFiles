package app.local1st.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class TvRelease(
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val downloadUrl: String,
)

private sealed interface UpdateCheckState {
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Available(val release: TvRelease) : UpdateCheckState
    data class Failed(val message: String) : UpdateCheckState
}

private object TvSelfUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest"
    private const val PREFS = "tv_self_update"
    private const val LAST_AUTO_CHECK = "last_auto_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val tvAssetPattern = Regex("^XFiles-TV-(.+)-b(\\d+)-debug\\.apk$")

    fun autoCheckDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_AUTO_CHECK, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun markAutoCheck(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(LAST_AUTO_CHECK, System.currentTimeMillis())
            .apply()
    }

    suspend fun check(): TvRelease? = withContext(Dispatchers.IO) {
        val connection = openConnection(RELEASE_API, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(body).getJSONArray("assets")
            var newest: TvRelease? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                val match = tvAssetPattern.matchEntire(name) ?: continue
                val build = match.groupValues[2].toIntOrNull() ?: continue
                val candidate = TvRelease(
                    versionName = match.groupValues[1],
                    buildNumber = build,
                    assetName = name,
                    downloadUrl = asset.getString("browser_download_url"),
                )
                if (newest == null || candidate.buildNumber > newest.buildNumber) {
                    newest = candidate
                }
            }
            newest?.takeIf { it.buildNumber > BuildConfig.VERSION_CODE }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadAndValidate(context: Context, release: TvRelease): File =
        withContext(Dispatchers.IO) {
            val updateDir = File(context.cacheDir, "tv-updates").apply { mkdirs() }
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
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun validateApk(context: Context, apk: File, release: TvRelease) {
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(0L),
        ) ?: error("Downloaded APK could not be read")
        if (packageInfo.packageName != context.packageName) {
            error("Downloaded APK is not XFiles TV")
        }
        if (packageInfo.longVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            error("Downloaded APK is not newer than the installed build")
        }
        if (packageInfo.longVersionCode != release.buildNumber.toLong()) {
            error("Downloaded APK build number does not match the GitHub asset")
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "XFiles-TV/${BuildConfig.VERSION_NAME}")
        }
}

@Composable
fun EditionStartupUpdateCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<TvRelease?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!TvSelfUpdater.autoCheckDue(context)) return@LaunchedEffect
        runCatching { TvSelfUpdater.check() }
            .onSuccess {
                TvSelfUpdater.markAutoCheck(context)
                release = it
            }
    }

    val available = release ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) release = null },
        title = { Text(stringResource(R.string.tv_update_available_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.tv_update_available_message,
                        available.versionName,
                        available.buildNumber,
                    ),
                )
                if (!TvSelfUpdater.canInstallPackages(context)) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tv_update_install_permission_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.tv_update_error, it),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (!TvSelfUpdater.canInstallPackages(context)) {
                        TvSelfUpdater.openInstallPermission(context)
                        return@TextButton
                    }
                    scope.launch {
                        downloading = true
                        errorMessage = null
                        runCatching { TvSelfUpdater.downloadAndValidate(context, available) }
                            .onSuccess { TvSelfUpdater.launchInstaller(context, it) }
                            .onFailure { errorMessage = it.message ?: it.javaClass.simpleName }
                        downloading = false
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (downloading) R.string.tv_update_downloading
                        else R.string.tv_update_install,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = { release = null }) {
                Text(stringResource(R.string.tv_update_later))
            }
        },
    )
}

@Composable
fun EditionUpdateSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Checking) }
    var downloading by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    fun checkForUpdate() {
        checkState = UpdateCheckState.Checking
        installError = null
        scope.launch {
            checkState = runCatching { TvSelfUpdater.check() }
                .fold(
                    onSuccess = { release ->
                        if (release == null) UpdateCheckState.UpToDate
                        else UpdateCheckState.Available(release)
                    },
                    onFailure = { UpdateCheckState.Failed(it.message ?: it.javaClass.simpleName) },
                )
        }
    }

    LaunchedEffect(Unit) { checkForUpdate() }

    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.tv_update_section_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.tv_update_current_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.tv_update_section_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            when (val state = checkState) {
                UpdateCheckState.Checking -> Text(stringResource(R.string.tv_update_checking))
                UpdateCheckState.UpToDate -> Text(stringResource(R.string.tv_update_up_to_date))
                is UpdateCheckState.Available -> {
                    Text(
                        stringResource(
                            R.string.tv_update_available_inline,
                            state.release.versionName,
                            state.release.buildNumber,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!TvSelfUpdater.canInstallPackages(context)) {
                        Text(
                            stringResource(R.string.tv_update_install_permission_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        enabled = !downloading,
                        onClick = {
                            if (!TvSelfUpdater.canInstallPackages(context)) {
                                TvSelfUpdater.openInstallPermission(context)
                                return@OutlinedButton
                            }
                            scope.launch {
                                downloading = true
                                installError = null
                                runCatching {
                                    TvSelfUpdater.downloadAndValidate(context, state.release)
                                }.onSuccess {
                                    TvSelfUpdater.launchInstaller(context, it)
                                }.onFailure {
                                    installError = it.message ?: it.javaClass.simpleName
                                }
                                downloading = false
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (downloading) R.string.tv_update_downloading
                                else R.string.tv_update_install,
                            ),
                        )
                    }
                }
                is UpdateCheckState.Failed -> Text(
                    stringResource(R.string.tv_update_error, state.message),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            installError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.tv_update_error, it),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = checkState !is UpdateCheckState.Checking && !downloading,
                onClick = { checkForUpdate() },
            ) {
                Text(stringResource(R.string.tv_update_check_now))
            }
        }
    }
}

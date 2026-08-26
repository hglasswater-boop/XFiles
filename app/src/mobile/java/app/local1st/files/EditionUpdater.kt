package app.local1st.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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

private data class MobileRelease(
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val downloadUrl: String,
)

private object MobileSelfUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest"
    private const val PREFS = "mobile_self_update"
    private const val LAST_AUTO_CHECK = "last_auto_check"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private val mobileAssetPattern = Regex("^XFiles-(?!TV-)(.+)-b(\\d+)-debug\\.apk$")

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

    suspend fun check(): MobileRelease? = withContext(Dispatchers.IO) {
        val connection = openConnection(RELEASE_API, "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("GitHub HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(body).getJSONArray("assets")
            var newest: MobileRelease? = null
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.optString("name")
                val match = mobileAssetPattern.matchEntire(name) ?: continue
                val build = match.groupValues[2].toIntOrNull() ?: continue
                val candidate = MobileRelease(
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

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, apk: File, release: MobileRelease) {
        val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("Downloaded APK could not be read")
        if (packageInfo.packageName != context.packageName) {
            error("Downloaded APK is not XFiles")
        }
        val downloadedBuild = packageInfo.versionCode.toLong()
        if (downloadedBuild <= BuildConfig.VERSION_CODE.toLong()) {
            error("Downloaded APK is not newer than the installed build")
        }
        if (downloadedBuild != release.buildNumber.toLong()) {
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
            setRequestProperty("User-Agent", "XFiles/${BuildConfig.VERSION_NAME}")
        }
}

@Composable
fun EditionStartupUpdateCheck() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<MobileRelease?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!MobileSelfUpdater.autoCheckDue(context)) return@LaunchedEffect
        runCatching { MobileSelfUpdater.check() }
            .onSuccess {
                MobileSelfUpdater.markAutoCheck(context)
                release = it
            }
    }

    val available = release ?: return
    AlertDialog(
        onDismissRequest = { if (!downloading) release = null },
        title = { Text(stringResource(R.string.mobile_update_available_title)) },
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
                enabled = !downloading,
                onClick = {
                    if (!MobileSelfUpdater.canInstallPackages(context)) {
                        MobileSelfUpdater.openInstallPermission(context)
                        return@TextButton
                    }
                    scope.launch {
                        downloading = true
                        errorMessage = null
                        runCatching { MobileSelfUpdater.downloadAndValidate(context, available) }
                            .onSuccess { MobileSelfUpdater.launchInstaller(context, it) }
                            .onFailure { errorMessage = it.message ?: it.javaClass.simpleName }
                        downloading = false
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (downloading) R.string.mobile_update_downloading
                        else R.string.mobile_update_install,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = { release = null }) {
                Text(stringResource(R.string.mobile_update_later))
            }
        },
    )
}

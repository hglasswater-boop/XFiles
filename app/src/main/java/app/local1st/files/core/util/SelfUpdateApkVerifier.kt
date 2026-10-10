package app.local1st.files.core.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Conservative preflight signing identity check before passing an update to PackageInstaller.
 * PackageInstaller still verifies the installed signature and requests user authorization.
 */
object SelfUpdateApkVerifier {
    fun sameSigningIdentity(installed: Set<String>, downloaded: Set<String>): Boolean =
        installed.isNotEmpty() && installed == downloaded

    @Suppress("DEPRECATION")
    fun requireMatchingInstalledSigner(
        context: Context,
        downloadedApk: File,
        expectedPackageName: String,
    ) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installed = try {
            pm.getPackageInfo(expectedPackageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            // Diagnostic package may be installed side-by-side for the first time.
            return
        }
        val downloaded = pm.getPackageArchiveInfo(downloadedApk.absolutePath, flags)
            ?: error("Downloaded APK signing identity could not be read")
        val installedSigners = signerCertificates(installed)
        val downloadedSigners = signerCertificates(downloaded)
        if (!sameSigningIdentity(installedSigners, downloadedSigners)) {
            error("Downloaded APK is not signed by the installed XFiles signing identity")
        }
    }

    @Suppress("DEPRECATION")
    private fun signerCertificates(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }
}

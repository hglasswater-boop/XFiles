package app.local1st.files.core.update

enum class SelfUpdateEdition {
    MOBILE,
    TV,
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

data class ResolvedUpdateRelease(
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val downloadUrl: String,
) {
    fun isNewerThan(installedBuild: Int): Boolean = buildNumber > installedBuild

    fun isInstallableOver(installedBuild: Int?, installedIsDebuggable: Boolean?): Boolean =
        isInstallableNormalBuild(buildNumber, installedBuild, installedIsDebuggable)
}

/**
 * Normal is the only self-update source. Equal-version replacement is permitted solely
 * to migrate an existing non-debuggable (formerly Nightly/stable) normal-package install.
 */
fun isInstallableNormalBuild(
    downloadedBuild: Int,
    installedBuild: Int?,
    installedIsDebuggable: Boolean?,
): Boolean = installedBuild == null ||
    downloadedBuild > installedBuild ||
    (downloadedBuild == installedBuild && installedIsDebuggable == false)

object SelfUpdateReleaseContract {
    const val DEBUG_LATEST_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest"
    const val DIAGNOSTIC_LATEST_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/diagnostic-latest"

    private val normalMobileAsset =
        Regex("^XFiles-(?!TV-|Diagnostic-)(.+)-b(\\d+)-debug\\.apk$")
    private val normalTvAsset = Regex("^XFiles-TV-(.+)-b(\\d+)-debug\\.apk$")
    private val diagnosticMobileAsset =
        Regex("^XFiles-Diagnostic-(.+)-b(\\d+)-debug\\.apk$")

    fun resolveNormal(
        edition: SelfUpdateEdition,
        assets: List<ReleaseAsset>,
    ): ResolvedUpdateRelease = resolvePatternedAsset(
        pattern = when (edition) {
            SelfUpdateEdition.MOBILE -> normalMobileAsset
            SelfUpdateEdition.TV -> normalTvAsset
        },
        assets = assets,
        missingMessage = "Release does not contain an update asset for $edition",
    )

    fun resolveDiagnosticMobile(assets: List<ReleaseAsset>): ResolvedUpdateRelease =
        resolvePatternedAsset(
            pattern = diagnosticMobileAsset,
            assets = assets,
            missingMessage = "Diagnostic release does not contain a mobile APK",
        )

    private fun resolvePatternedAsset(
        pattern: Regex,
        assets: List<ReleaseAsset>,
        missingMessage: String,
    ): ResolvedUpdateRelease = assets.mapNotNull { asset ->
        val match = pattern.matchEntire(asset.name) ?: return@mapNotNull null
        val build = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        ResolvedUpdateRelease(
            versionName = match.groupValues[1],
            buildNumber = build,
            assetName = asset.name,
            downloadUrl = asset.downloadUrl,
        )
    }.maxByOrNull { it.buildNumber }
        ?: throw IllegalArgumentException(missingMessage)
}

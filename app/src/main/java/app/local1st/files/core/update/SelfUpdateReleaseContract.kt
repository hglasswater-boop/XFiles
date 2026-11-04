package app.local1st.files.core.update

/** Stable release APKs are non-debuggable; rolling debug APKs remain a migration bridge. */
enum class SelfUpdateSource {
    STABLE,
    DEBUG,
}

data class StableReleaseMetadata(
    val tagName: String,
    val body: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

data class ResolvedUpdateRelease(
    val versionName: String,
    val buildNumber: Int,
    val assetName: String,
    val downloadUrl: String,
    val source: SelfUpdateSource = SelfUpdateSource.DEBUG,
) {
    fun isNewerThan(installedBuild: Int): Boolean = buildNumber > installedBuild

    fun isInstallableOver(installedBuild: Int?, installedIsDebuggable: Boolean?): Boolean =
        isInstallableNormalBuild(buildNumber, installedBuild, installedIsDebuggable)
}

/**
 * Both stable and rolling debug updates require a strictly newer Android build number.
 * Never offer a same-build update that would replace a stable install with a debug APK.
 */
@Suppress("UNUSED_PARAMETER")
fun isInstallableNormalBuild(
    downloadedBuild: Int,
    installedBuild: Int?,
    installedIsDebuggable: Boolean?,
): Boolean = downloadedBuild > 0 && (installedBuild == null || downloadedBuild > installedBuild)

object SelfUpdateReleaseContract {
    const val STABLE_LATEST_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/latest"
    const val DEBUG_LATEST_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest"
    const val DIAGNOSTIC_LATEST_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/diagnostic-latest"

    private val stableTag = Regex("""^v(\d+\.\d+\.\d+-smb)$""")
    private val stableBuildLine =
        Regex("""XFiles\s+(\d+\.\d+\.\d+-smb)\s+·\s+build\s+(\d+)\s+\([a-f0-9]{40}\)""")

    private val normalPhoneAsset =
        Regex("^XFiles-(?!TV-|Diagnostic-)(.+)-b(\\d+)-debug\\.apk$")
    private val diagnosticMobileAsset =
        Regex("^XFiles-Diagnostic-(.+)-b(\\d+)-debug\\.apk$")

    /** Resolves the existing stable release format without guessing APK versionCode from a tag. */
    fun resolveStable(release: StableReleaseMetadata): ResolvedUpdateRelease {
        require(!release.draft && !release.prerelease) { "Stable release must be published" }
        val version = stableTag.matchEntire(release.tagName)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid stable release tag")
        val buildMatch = stableBuildLine.find(release.body)
            ?: throw IllegalArgumentException("Stable release is missing build metadata")
        require(buildMatch.groupValues[1] == version) { "Stable build metadata does not match tag" }
        val build = buildMatch.groupValues[2].toIntOrNull()
            ?.takeIf { it in 1..2_100_000_000 }
            ?: throw IllegalArgumentException("Invalid stable release build number")
        val expectedName = "XFiles-${version}.apk"
        val asset = release.assets.singleOrNull { it.name == expectedName }
            ?: throw IllegalArgumentException("Stable release does not contain the phone APK")
        require(asset.downloadUrl.isNotBlank()) { "Stable release asset URL is missing" }
        return ResolvedUpdateRelease(
            versionName = version,
            buildNumber = build,
            assetName = asset.name,
            downloadUrl = asset.downloadUrl,
            source = SelfUpdateSource.STABLE,
        )
    }

    /** Never downgrade; a stable APK wins a tie when both are eligible for installation. */
    fun selectNormalUpdate(
        stable: ResolvedUpdateRelease?,
        debug: ResolvedUpdateRelease?,
        installedBuild: Int?,
        installedIsDebuggable: Boolean?,
    ): ResolvedUpdateRelease? = listOfNotNull(stable, debug)
        .filter { it.isInstallableOver(installedBuild, installedIsDebuggable) }
        .maxWithOrNull(compareBy<ResolvedUpdateRelease> { it.buildNumber }
            .thenBy { if (it.source == SelfUpdateSource.STABLE) 1 else 0 })

    fun resolveNormal(assets: List<ReleaseAsset>): ResolvedUpdateRelease =
        resolvePatternedAsset(
            pattern = normalPhoneAsset,
            assets = assets,
            missingMessage = "Release does not contain a phone update APK",
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

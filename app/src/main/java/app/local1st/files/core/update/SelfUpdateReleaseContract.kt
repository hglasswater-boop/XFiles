package app.local1st.files.core.update

enum class SelfUpdateTrack(
    val storedValue: String,
    val apiUrl: String,
) {
    NORMAL("normal", SelfUpdateReleaseContract.DEBUG_LATEST_API),
    NIGHTLY("nightly", SelfUpdateReleaseContract.NIGHTLY_API),
    ;

    companion object {
        fun fromStoredValue(value: String?): SelfUpdateTrack =
            entries.firstOrNull { it.storedValue == value } ?: NORMAL
    }
}

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
}

object SelfUpdateReleaseContract {
    const val DEBUG_LATEST_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest"
    const val NIGHTLY_API =
        "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/nightly"

    private val normalMobileAsset =
        Regex("^XFiles-(?!TV-|Diagnostic-)(.+)-b(\\d+)-debug\\.apk$")
    private val normalTvAsset = Regex("^XFiles-TV-(.+)-b(\\d+)-debug\\.apk$")
    private val nightlyMetadata = Regex(
        pattern = "(?m)^XFiles\\s+(.+?)\\s+·\\s+build\\s+(\\d+)\\s+\\([^)]+\\)\\.?\\s*$",
    )

    fun resolve(
        track: SelfUpdateTrack,
        edition: SelfUpdateEdition,
        releaseBody: String,
        assets: List<ReleaseAsset>,
    ): ResolvedUpdateRelease = when (track) {
        SelfUpdateTrack.NORMAL -> resolveNormal(edition, assets)
        SelfUpdateTrack.NIGHTLY -> resolveNightly(edition, releaseBody, assets)
    }

    private fun resolveNormal(
        edition: SelfUpdateEdition,
        assets: List<ReleaseAsset>,
    ): ResolvedUpdateRelease {
        val pattern = when (edition) {
            SelfUpdateEdition.MOBILE -> normalMobileAsset
            SelfUpdateEdition.TV -> normalTvAsset
        }
        return assets.mapNotNull { asset ->
            val match = pattern.matchEntire(asset.name) ?: return@mapNotNull null
            val build = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            ResolvedUpdateRelease(
                versionName = match.groupValues[1],
                buildNumber = build,
                assetName = asset.name,
                downloadUrl = asset.downloadUrl,
            )
        }.maxByOrNull { it.buildNumber }
            ?: throw IllegalArgumentException("Release does not contain an update asset for $edition")
    }

    private fun resolveNightly(
        edition: SelfUpdateEdition,
        releaseBody: String,
        assets: List<ReleaseAsset>,
    ): ResolvedUpdateRelease {
        val metadata = nightlyMetadata.find(releaseBody)
            ?: throw IllegalArgumentException("Nightly release metadata is missing or malformed")
        val version = metadata.groupValues[1]
        val build = metadata.groupValues[2].toIntOrNull()
            ?: throw IllegalArgumentException("Nightly build number is invalid")
        val expectedAsset = when (edition) {
            SelfUpdateEdition.MOBILE -> "XFiles-nightly.apk"
            SelfUpdateEdition.TV -> "XFiles-TV-nightly.apk"
        }
        val asset = assets.singleOrNull { it.name == expectedAsset }
            ?: throw IllegalArgumentException("Nightly release does not contain $expectedAsset")
        return ResolvedUpdateRelease(
            versionName = version,
            buildNumber = build,
            assetName = asset.name,
            downloadUrl = asset.downloadUrl,
        )
    }
}

package app.local1st.files.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateReleaseContractTest {
    @Test
    fun normalMobileKeepsDebugLatestContract() {
        val release = SelfUpdateReleaseContract.resolveNormal(
            edition = SelfUpdateEdition.MOBILE,
            assets = listOf(
                ReleaseAsset("XFiles-1.4.1-smb-b29850001-debug.apk", "mobile-old"),
                ReleaseAsset("XFiles-1.4.1-smb-b29850002-debug.apk", "mobile-new"),
                ReleaseAsset("XFiles-TV-1.4.1-smb-b29850003-debug.apk", "tv"),
                ReleaseAsset("XFiles-Diagnostic-1.4.1-smb-b29850004-debug.apk", "diagnostic"),
                ReleaseAsset("XFiles-nightly.apk", "legacy"),
            ),
        )
        assertEquals(
            "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest",
            SelfUpdateReleaseContract.DEBUG_LATEST_API,
        )
        assertEquals("1.4.1-smb", release.versionName)
        assertEquals(29850002, release.buildNumber)
        assertEquals("XFiles-1.4.1-smb-b29850002-debug.apk", release.assetName)
        assertEquals("mobile-new", release.downloadUrl)
    }

    @Test
    fun normalTvKeepsDebugLatestContract() {
        val release = SelfUpdateReleaseContract.resolveNormal(
            edition = SelfUpdateEdition.TV,
            assets = listOf(
                ReleaseAsset("XFiles-1.4.1-smb-b29850002-debug.apk", "mobile"),
                ReleaseAsset("XFiles-TV-1.4.1-smb-b29850003-debug.apk", "tv"),
            ),
        )
        assertEquals("1.4.1-smb", release.versionName)
        assertEquals(29850003, release.buildNumber)
        assertEquals("XFiles-TV-1.4.1-smb-b29850003-debug.apk", release.assetName)
        assertEquals("tv", release.downloadUrl)
    }

    @Test
    fun normalRejectsMissingOrMalformedEditionAssets() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfUpdateReleaseContract.resolveNormal(
                SelfUpdateEdition.TV,
                listOf(ReleaseAsset("XFiles-nightly.apk", "old-nightly")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SelfUpdateReleaseContract.resolveNormal(
                SelfUpdateEdition.MOBILE,
                listOf(ReleaseAsset("XFiles-1.4.1-smb-bnotanumber-debug.apk", "bad")),
            )
        }
    }

    @Test
    fun diagnosticResolutionRemainsIndependentFromNormal() {
        val release = SelfUpdateReleaseContract.resolveDiagnosticMobile(
            listOf(
                ReleaseAsset("XFiles-1.4.1-smb-b29850002-debug.apk", "normal"),
                ReleaseAsset("XFiles-Diagnostic-1.4.1-smb-diagnostic-b29850003-debug.apk", "old"),
                ReleaseAsset("XFiles-Diagnostic-1.4.1-smb-diagnostic-b29850004-debug.apk", "new"),
            ),
        )
        assertEquals(
            "https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/diagnostic-latest",
            SelfUpdateReleaseContract.DIAGNOSTIC_LATEST_API,
        )
        assertEquals("1.4.1-smb-diagnostic", release.versionName)
        assertEquals(29850004, release.buildNumber)
        assertEquals("new", release.downloadUrl)
    }

    @Test
    fun newerNormalBuildIsAcceptedAndOlderIsRejected() {
        assertTrue(isInstallableNormalBuild(201, 200, true))
        assertTrue(isInstallableNormalBuild(201, 200, false))
        assertFalse(isInstallableNormalBuild(199, 200, true))
        assertFalse(isInstallableNormalBuild(199, 200, false))
    }

    @Test
    fun equalBuildPermitsLegacyNonDebuggableMigrationOnly() {
        assertTrue(isInstallableNormalBuild(200, 200, false))
        assertFalse(isInstallableNormalBuild(200, 200, true))
        assertFalse(isInstallableNormalBuild(200, 200, null))
    }

    @Test
    fun missingInstallAcceptsNormalRelease() {
        assertTrue(isInstallableNormalBuild(200, null, null))
        assertTrue(isInstallableNormalBuild(200, null, true))
    }

    @Test
    fun resolvedReleaseUsesSameNormalBuildPolicy() {
        val release = ResolvedUpdateRelease(
            versionName = "1.4.1-smb",
            buildNumber = 200,
            assetName = "XFiles-1.4.1-smb-b200-debug.apk",
            downloadUrl = "download",
        )
        assertTrue(release.isInstallableOver(200, false))
        assertFalse(release.isInstallableOver(200, true))
        assertFalse(release.isInstallableOver(201, false))
        assertTrue(release.isNewerThan(199))
        assertFalse(release.isNewerThan(200))
    }
}

package app.local1st.files.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateReleaseContractTest {
    @Test
    fun normalMobileKeepsDebugLatestContract() {
        val assets = listOf(
            ReleaseAsset("XFiles-1.4.1-smb-b29850001-debug.apk", "mobile-old"),
            ReleaseAsset("XFiles-1.4.1-smb-b29850002-debug.apk", "mobile-new"),
            ReleaseAsset("XFiles-TV-1.4.1-smb-b29850003-debug.apk", "tv"),
            ReleaseAsset("XFiles-Diagnostic-1.4.1-smb-b29850004-debug.apk", "diagnostic"),
        )

        val release = SelfUpdateReleaseContract.resolve(
            track = SelfUpdateTrack.NORMAL,
            edition = SelfUpdateEdition.MOBILE,
            releaseBody = "",
            assets = assets,
        )

        assertEquals(SelfUpdateReleaseContract.DEBUG_LATEST_API, SelfUpdateTrack.NORMAL.apiUrl)
        assertEquals("1.4.1-smb", release.versionName)
        assertEquals(29850002, release.buildNumber)
        assertEquals("XFiles-1.4.1-smb-b29850002-debug.apk", release.assetName)
        assertEquals("mobile-new", release.downloadUrl)
    }

    @Test
    fun normalTvKeepsDebugLatestContract() {
        val release = SelfUpdateReleaseContract.resolve(
            track = SelfUpdateTrack.NORMAL,
            edition = SelfUpdateEdition.TV,
            releaseBody = "",
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
    fun nightlyMobileUsesCanonicalMetadataAndExactAsset() {
        val release = SelfUpdateReleaseContract.resolve(
            track = SelfUpdateTrack.NIGHTLY,
            edition = SelfUpdateEdition.MOBILE,
            releaseBody = """
                **NAS-focused Android file manager.**

                XFiles 1.4.1-smb · build 29852550 (7e89e2c2ef6768e601cf2dd6f158dbc4aa5df19b).
            """.trimIndent(),
            assets = listOf(
                ReleaseAsset("XFiles-nightly.apk", "mobile-nightly"),
                ReleaseAsset("XFiles-TV-nightly.apk", "tv-nightly"),
            ),
        )

        assertEquals(SelfUpdateReleaseContract.NIGHTLY_API, SelfUpdateTrack.NIGHTLY.apiUrl)
        assertEquals("1.4.1-smb", release.versionName)
        assertEquals(29852550, release.buildNumber)
        assertEquals("XFiles-nightly.apk", release.assetName)
        assertEquals("mobile-nightly", release.downloadUrl)
    }

    @Test
    fun nightlyTvSelectsTvAsset() {
        val release = SelfUpdateReleaseContract.resolve(
            track = SelfUpdateTrack.NIGHTLY,
            edition = SelfUpdateEdition.TV,
            releaseBody = "XFiles 1.4.1-smb · build 29852550 (deadbeef).",
            assets = listOf(
                ReleaseAsset("XFiles-nightly.apk", "mobile-nightly"),
                ReleaseAsset("XFiles-TV-nightly.apk", "tv-nightly"),
            ),
        )

        assertEquals("XFiles-TV-nightly.apk", release.assetName)
        assertEquals("tv-nightly", release.downloadUrl)
    }

    @Test
    fun nightlyRejectsMalformedMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfUpdateReleaseContract.resolve(
                track = SelfUpdateTrack.NIGHTLY,
                edition = SelfUpdateEdition.MOBILE,
                releaseBody = "nightly build without canonical metadata",
                assets = listOf(ReleaseAsset("XFiles-nightly.apk", "mobile-nightly")),
            )
        }
    }

    @Test
    fun nightlyRejectsMissingEditionAsset() {
        assertThrows(IllegalArgumentException::class.java) {
            SelfUpdateReleaseContract.resolve(
                track = SelfUpdateTrack.NIGHTLY,
                edition = SelfUpdateEdition.TV,
                releaseBody = "XFiles 1.4.1-smb · build 29852550 (deadbeef).",
                assets = listOf(ReleaseAsset("XFiles-nightly.apk", "mobile-nightly")),
            )
        }
    }

    @Test
    fun diagnosticResolutionRemainsIndependentFromNormalTrack() {
        val release = SelfUpdateReleaseContract.resolveDiagnosticMobile(
            listOf(
                ReleaseAsset("XFiles-1.4.1-smb-b29850002-debug.apk", "normal"),
                ReleaseAsset("XFiles-Diagnostic-1.4.1-smb-diagnostic-b29850003-debug.apk", "old"),
                ReleaseAsset("XFiles-Diagnostic-1.4.1-smb-diagnostic-b29850004-debug.apk", "new"),
            ),
        )

        assertEquals(SelfUpdateReleaseContract.DIAGNOSTIC_LATEST_API, release.apiUrl)
        assertEquals("1.4.1-smb-diagnostic", release.release.versionName)
        assertEquals(29850004, release.release.buildNumber)
        assertEquals("new", release.release.downloadUrl)
    }

    @Test
    fun updateAvailabilityUsesStrictBuildOrdering() {
        val release = ResolvedUpdateRelease(
            versionName = "1.4.1-smb",
            buildNumber = 200,
            assetName = "XFiles-nightly.apk",
            downloadUrl = "download",
        )

        assertTrue(release.isNewerThan(199))
        assertFalse(release.isNewerThan(200))
        assertFalse(release.isNewerThan(201))
    }

    @Test
    fun storedTrackDefaultsToNormalAndRoundTrips() {
        assertEquals(SelfUpdateTrack.NORMAL, SelfUpdateTrack.fromStoredValue(null))
        assertEquals(SelfUpdateTrack.NORMAL, SelfUpdateTrack.fromStoredValue("unknown"))
        assertEquals(SelfUpdateTrack.NORMAL, SelfUpdateTrack.fromStoredValue("normal"))
        assertEquals(SelfUpdateTrack.NIGHTLY, SelfUpdateTrack.fromStoredValue("nightly"))
        assertEquals("normal", SelfUpdateTrack.NORMAL.storedValue)
        assertEquals("nightly", SelfUpdateTrack.NIGHTLY.storedValue)
    }
}

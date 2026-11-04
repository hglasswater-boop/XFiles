package app.local1st.files.docs

import java.io.File
import java.util.Properties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentationConsistencyTest {

    @Test
    fun readmesMatchBuildMetadataAndDoNotReintroduceRemovedFeatures() {
        val repo = File(requireNotNull(System.getProperty("xfiles.repo")))
        val buildScript = File(repo, "app/build.gradle.kts").readText()
        val versionName = Properties().apply {
            File(repo, "version.properties").inputStream().use { load(it) }
        }.getProperty("versionName").orEmpty().also {
            assertTrue("version.properties must define versionName", it.isNotBlank())
        }

        val applicationId = requireMatch(
            buildScript,
            Regex("applicationId\\s*=\\s*\"([^\"]+)\""),
            "applicationId",
        )
        val minSdk = requireMatch(buildScript, Regex("\\bminSdk\\s*=\\s*(\\d+)"), "minSdk")
        val compileSdk = requireMatch(buildScript, Regex("\\bcompileSdk\\s*=\\s*(\\d+)"), "compileSdk")
        val targetSdk = requireMatch(buildScript, Regex("\\btargetSdk\\s*=\\s*(\\d+)"), "targetSdk")

        val readmes = listOf("README.md", "README.ja.md", "README.zh-CN.md")
        readmes.forEach { path ->
            val text = File(repo, path).readText()
            assertContains(path, text, versionName)
            assertContains(path, text, applicationId)
            assertContains(path, text, "minSdk $minSdk")
            if (compileSdk == targetSdk) {
                assertContains(path, text, "compile/target SDK $compileSdk")
            } else {
                assertContains(path, text, "compile SDK $compileSdk")
                assertContains(path, text, "target SDK $targetSdk")
            }

            REMOVED_FEATURE_MARKERS.forEach { marker ->
                assertFalse(
                    "$path must not advertise removed feature marker '$marker'",
                    text.contains(marker, ignoreCase = true),
                )
            }
            STALE_NETWORK_CLAIMS.forEach { claim ->
                assertFalse(
                    "$path must not claim XFiles has no network capability: '$claim'",
                    text.contains(claim, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun activeBuildAndCiShipOnePhoneApp() {
        val repo = File(requireNotNull(System.getProperty("xfiles.repo")))
        val gradle = File(repo, "app/build.gradle.kts").readText()
        assertFalse("Product flavors have been retired", gradle.contains("productFlavors"))
        assertFalse("Cast dependency must not be mobile flavor-specific", gradle.contains("mobileImplementation"))
        assertTrue("Cast must remain available", gradle.contains("implementation(libs.media3.cast)"))
        assertFalse(File(repo, "app/src/tv").exists())
        assertFalse(File(repo, "app/src/mobile").exists())

        val workflows = listOf(
            ".github/workflows/release.yml",
            ".github/workflows/personal-fork-ci.yml",
            ".github/workflows/debug-artifact-request.yml",
            ".github/workflows/rust-smb-preview.yml",
        )
        val tvMarkers = listOf(
            "assembleTv", "assembleMobile", "testTv", "testMobile", "lintVitalTv",
            "lintVitalMobile", "bundleMobile", "connectedMobile", "XFiles-TV-",
            "app-tv-", "app-mobile-", "tv_name", "tv_apk", "DEBUG_TV_APK",
            "app.local1st.files.tv",
        )
        workflows.forEach { path ->
            val workflow = File(repo, path).readText()
            tvMarkers.forEach { marker ->
                assertFalse("$path contains retired edition marker '$marker'", workflow.contains(marker))
            }
        }
    }

    @Test
    fun currentPublicDocsDescribeOnlyTheSupportedApp() {
        val repo = File(requireNotNull(System.getProperty("xfiles.repo")))
        val publicDocs = listOf("README.md", "README.ja.md", "README.zh-CN.md")
            .map { File(repo, it) } +
            File(repo, "docs").walkTopDown().filter { it.isFile && it.extension == "md" }.toList() +
            File(repo, "fastlane/metadata/android").walkTopDown()
                .filter { it.isFile && it.extension == "txt" }.toList() +
            listOf(File(repo, ".github/workflows/release.yml"))

        val deprecatedClaims = listOf(
            "Android TV", "Google TV", "XFiles-TV-", "TV版", "TV 版",
            "Mobile/TV", "mobile/TV", "Android_TV_RETIREMENT",
            "app.local1st.files.tv",
        )
        publicDocs.forEach { file ->
            val text = file.readText()
            deprecatedClaims.forEach { claim ->
                assertFalse("${file.path} contains obsolete edition description: $claim", text.contains(claim))
            }
        }
        assertFalse(File(repo, "docs/ANDROID_TV_RETIREMENT.md").exists())
    }

    @Test
    fun actionsArtifactsAreShortLivedAndSeparateFromInstallReleases() {
        val repo = File(requireNotNull(System.getProperty("xfiles.repo")))
        val buildCi = File(repo, ".github/workflows/personal-fork-ci.yml").readText()
        val debugRequest = File(repo, ".github/workflows/debug-artifact-request.yml").readText()
        val rustPreview = File(repo, ".github/workflows/rust-smb-preview.yml").readText()
        val release = File(repo, ".github/workflows/release.yml").readText()
        val policy = File(repo, "docs/UPDATE_CHANNELS.md").readText()

        assertTrue(policy.contains("GitHub Actions artifact retention"))
        assertTrue("No duplicate main debug artifact", buildCi.contains(
            "- name: Upload signed debug APKs\n        if: github.ref != 'refs/heads/main'"
        ))
        assertTrue("Debug artifacts expire in one day",
            buildCi.contains("retention-days: 1"))
        assertTrue("Emulator reports only on failure",
            buildCi.contains("- name: Upload emulator test reports\n        if: failure()"))
        assertTrue("On-demand debug artifacts expire in one day",
            debugRequest.contains("retention-days: 1"))
        assertTrue("Rust preview artifacts expire in one day",
            rustPreview.contains("retention-days: 1"))
        assertTrue("Play submission AAB is retained seven days",
            release.contains("retention-days: 7"))
        assertTrue("Rolling update still uses GitHub Releases",
            buildCi.contains("gh release create debug-latest"))
        assertTrue("Stable APK remains a GitHub Release asset",
            release.contains("name: Publish release"))
    }

    @Test
    fun declaredStableVersionHasMatchingReleaseNotes() {
        val repo = File(requireNotNull(System.getProperty("xfiles.repo")))
        val versionName = Properties().apply {
            File(repo, "version.properties").inputStream().use { load(it) }
        }.getProperty("versionName").orEmpty()
        assertTrue("Stable SMB version must follow expected pattern",
            Regex("""\d+\.\d+\.\d+-smb""").matches(versionName))
        val notes = File(repo, "docs/releases/$versionName.md")
        assertTrue("Missing release notes for $versionName", notes.isFile)
        assertTrue("Release notes must mention $versionName", notes.readText().contains(versionName))
    }

    private fun requireMatch(text: String, regex: Regex, label: String): String =
        requireNotNull(regex.find(text)?.groupValues?.get(1)) { "Could not read $label from app/build.gradle.kts" }

    private fun assertContains(path: String, text: String, expected: String) {
        assertTrue("$path must contain '$expected'", text.contains(expected))
    }

    private companion object {
        val REMOVED_FEATURE_MARKERS = listOf(
            "Shizuku",
            "App Manager",
            "root://",
            "apps://",
        )

        val STALE_NETWORK_CLAIMS = listOf(
            "No network",
            "does not request `INTERNET`",
            "`INTERNET` 権限を要求しません",
            "不请求 `INTERNET` 权限",
        )
    }
}

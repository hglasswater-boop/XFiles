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
    fun activeBuildAndCiHaveNoTvEdition() {
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

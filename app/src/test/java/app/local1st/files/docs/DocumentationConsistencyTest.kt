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
            File(repo, "version.properties").inputStream().use(::load)
        }.getProperty("versionName").orEmpty().also {
            assertTrue("version.properties must define versionName", it.isNotBlank())
        }

        val applicationId = requireMatch(buildScript, Regex("""applicationId\s*=\s*\"([^\"]+)\"""), "applicationId")
        val tvApplicationIdSuffix = requireMatch(
            buildScript,
            Regex("""applicationIdSuffix\s*=\s*\"([^\"]+)\"""),
            "TV applicationIdSuffix",
        )
        val minSdk = requireMatch(buildScript, Regex("""\bminSdk\s*=\s*(\d+)"""), "minSdk")
        val compileSdk = requireMatch(buildScript, Regex("""\bcompileSdk\s*=\s*(\d+)"""), "compileSdk")
        val targetSdk = requireMatch(buildScript, Regex("""\btargetSdk\s*=\s*(\d+)"""), "targetSdk")
        val tvApplicationId = applicationId + tvApplicationIdSuffix

        val readmes = listOf("README.md", "README.ja.md", "README.zh-CN.md")
        readmes.forEach { path ->
            val text = File(repo, path).readText()
            assertContains(path, text, versionName)
            assertContains(path, text, applicationId)
            assertContains(path, text, tvApplicationId)
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

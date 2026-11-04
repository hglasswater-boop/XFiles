package app.local1st.files.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents a locale advertised by Android from silently falling back to English strings. */
class LocalizationResourcesTest {

    @Test
    fun everySupportedLocaleHasTheCompleteDefaultResourceSet() {
        val resDir = File(requireNotNull(System.getProperty("xfiles.repo")), "app/src/main/res")
        val default = readResources(File(resDir, "values/strings.xml"))
        val locales = resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it to File(it, "strings.xml") }
            .filter { (_, file) -> file.isFile }

        assertTrue("At least one translated locale is required", locales.isNotEmpty())
        locales.forEach { (dir, file) ->
            val translated = readResources(file)
            val missing = default.keys - translated.keys
            val extra = translated.keys - default.keys
            assertTrue("Unexpected resources in ${dir.name}: $extra", extra.isEmpty())
            assertTrue(
                "Missing required translations in ${dir.name}: ${missing - PHONE_FALLBACK_STRINGS}",
                missing.all { it in PHONE_FALLBACK_STRINGS },
            )
            if (dir.name == "values-ja") {
                assertTrue("Japanese phone UI translations must remain complete", missing.isEmpty())
            }
            assertEquals("The product name must remain English in ${dir.name}", "XFiles", translated["app_name"])
            translated.forEach { (name, localized) ->
                assertEquals(
                    "Format placeholders changed for $name in ${dir.name}",
                    placeholders(requireNotNull(default[name])),
                    placeholders(localized),
                )
            }
        }
    }

    private fun readResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, String>()
        val root = document.documentElement
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index)
            if (node.nodeName !in setOf("string", "plurals")) continue
            // Brand and command names are marked translatable="false" and only exist in values/.
            if (node.attributes?.getNamedItem("translatable")?.nodeValue == "false") continue
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            val value = if (node.nodeName == "plurals") {
                buildString {
                    for (itemIndex in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(itemIndex)
                        if (item.nodeName == "item") append(item.textContent)
                    }
                }
            } else {
                node.textContent
            }
            check(result.put(name, value) == null) { "Duplicate resource $name in ${file.path}" }
        }
        return result
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toSet().sorted()

    private companion object {
        // Previously mobile-flavor-only text shipped with English fallback and Japanese translations.
        // Merging it into main must not silently downgrade previously complete translations.
        val PHONE_FALLBACK_STRINGS = setOf(
            "mobile_update_available_title",
            "mobile_diagnostic_available_title",
            "mobile_update_available_message",
            "mobile_update_install_permission_hint",
            "mobile_update_install",
            "mobile_update_downloading",
            "mobile_update_preparing_install",
            "mobile_update_later",
            "mobile_update_error",
            "update_settings_title",
            "update_current_version",
            "update_auto_check",
            "update_auto_check_summary",
            "update_last_checked",
            "update_never_checked",
            "update_check_now",
            "update_checking",
            "update_up_to_date",
            "update_normal_up_to_date",
            "update_diagnostic_up_to_date",
            "update_install_latest_normal",
            "update_install_diagnostic",
            "update_diagnostic_summary",
            "update_check_failed",
            "cast_now_playing",
            "cast_jump_to_current_storyboard",
            "player_vertical_storyboard",
        )

        val PLACEHOLDER = Regex("%(?:[1-9]\\d*\\$)?[ds]")
    }
}

package eu.elraro.babytracker.ui

import eu.elraro.babytracker.ui.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppLanguageTest {
    @Test
    fun `stored language tags map to the offered languages`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(""))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("en"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags("es"))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags("es-ES,en-US"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags("EN-gb"))
        // A language we do not ship falls back to following the system.
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags("fr-FR"))
    }

    @Test
    fun `the language list matches the locale config shown by the system`() {
        val config = File("src/main/res/xml/locales_config.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val nodes = document.getElementsByTagName("locale")
        val declared =
            (0 until nodes.length).map {
                nodes
                    .item(it)
                    .attributes
                    .getNamedItem("android:name")
                    .nodeValue
            }
        assertEquals(AppLanguage.entries.mapNotNull { it.tag }, declared)
    }

    @Test
    fun `every translated string keeps the placeholders of the English text`() {
        val placeholders = Regex("""%(\d+\$)?[sd]""")

        // Covers <string> and every <item> of <plurals>; an item is keyed by its parent's name.
        fun strings(path: String): Map<String, Set<List<String>>> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            return listOf("string", "item")
                .flatMap { tag ->
                    val nodes = doc.getElementsByTagName(tag)
                    (0 until nodes.length).map { i ->
                        val node = nodes.item(i)
                        val named = if (tag == "item") node.parentNode else node
                        val found =
                            placeholders
                                .findAll(node.textContent)
                                .map { it.value }
                                .sorted()
                                .toList()
                        named.attributes.getNamedItem("name").nodeValue to found
                    }
                }.groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        }
        val english = strings("src/main/res/values/strings.xml")
        val spanish = strings("src/main/res/values-es/strings.xml")
        spanish.forEach { (name, found) -> assertEquals("placeholders of $name", english[name], found) }
    }
}

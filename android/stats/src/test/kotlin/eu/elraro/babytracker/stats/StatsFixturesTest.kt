package eu.elraro.babytracker.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The Kotlin engine must reproduce, value for value, the golden files in testdata/stats
 * that the Go engine produced. A difference means the two engines no longer implement
 * the same definitions (docs/STATISTICS.md §1).
 */
class StatsFixturesTest {
    // Nulls and defaults are written out, as the Go encoder does.
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    private fun fixtures(): List<File> {
        val dir = File("../../testdata/stats")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no fixtures in ${dir.absolutePath}", files.isNotEmpty())
        return files
    }

    private fun JsonElement?.textOrNull(): String? =
        if (this == null ||
            this is JsonNull
        ) {
            null
        } else {
            jsonPrimitive.content
        }

    private fun input(fixture: JsonObject): StatsInput {
        val input = fixture["input"]!!.jsonObject
        return StatsInput(
            timezone = input["timezone"]!!.jsonPrimitive.content,
            end = input["end"]!!.jsonPrimitive.content,
            days = input["days"]!!.jsonPrimitive.int,
            now = Instant.parse(input["now"]!!.jsonPrimitive.content),
            events =
                input["events"]!!.jsonArray.map { element ->
                    val e = element.jsonObject
                    StatEvent(
                        id = e["id"]!!.jsonPrimitive.content,
                        type = e["type"]!!.jsonPrimitive.content,
                        startedAt = Instant.parse(e["started_at"]!!.jsonPrimitive.content),
                        endedAt = e["ended_at"].textOrNull()?.let(Instant::parse),
                        timezone = e["timezone"]!!.jsonPrimitive.content,
                        amountMl = e["amount_ml"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
                        diaperKind = e["diaper_kind"].textOrNull(),
                        sleepKind = e["sleep_kind"].textOrNull(),
                    )
                },
        )
    }

    @Test
    fun `every fixture is reproduced exactly`() {
        for (file in fixtures()) {
            val fixture = Json.parseToJsonElement(file.readText()).jsonObject
            val actual = json.encodeToJsonElement(Stats.compute(input(fixture)))
            assertEquals(file.name, fixture["expected"], actual)
        }
    }

    @Test
    fun `the order of the input does not matter`() {
        val fixture = Json.parseToJsonElement(File("../../testdata/stats/month_30d.json").readText()).jsonObject
        val input = input(fixture)
        val reversed = Stats.compute(input.copy(events = input.events.reversed()))
        assertEquals(fixture["expected"], json.encodeToJsonElement(reversed))
    }

    @Test
    fun `invalid input is refused`() {
        val now = Instant.parse("2026-10-08T12:00:00Z")
        listOf(
            StatsInput("Mars/Olympus", "2026-10-08", 7, now, emptyList()),
            StatsInput("Local", "2026-10-08", 7, now, emptyList()),
            StatsInput("Europe/Madrid", "8/10/2026", 7, now, emptyList()),
            StatsInput("Europe/Madrid", "2026-10-08", 0, now, emptyList()),
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { Stats.compute(input) }
        }
    }

    @Test
    fun `numeric helpers follow the documented rounding`() {
        assertEquals(3, percentile(listOf(1, 2, 3, 4), 50)) // 2.5 rounds half up
        assertEquals(2, percentile(listOf(1, 2, 3, 4), 25)) // 1.75
        assertEquals(5790, percentile(listOf(5400, 5460, 6120, 6900), 50))
        assertEquals(2, meanHalfUp(listOf(1, 2)))
        assertEquals(1, meanHalfUp(listOf(1, 1, 2)))
        assertEquals(800, spearmanX1000(listOf(1, 2, 3, 4, 5), listOf(1, 3, 2, 5, 4)))
        assertEquals(949, spearmanX1000(listOf(10, 20, 20, 30), listOf(1, 2, 3, 4)))
        assertEquals(-1000, spearmanX1000(listOf(1, 2, 3), listOf(3, 2, 1)))
        assertNull(spearmanX1000(listOf(5, 5, 5), listOf(1, 2, 3)))
    }
}

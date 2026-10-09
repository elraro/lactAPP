package eu.elraro.babytracker.stats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** The Kotlin estimate must equal, field for field, the golden files produced by the Go engine. */
class NextSleepFixturesTest {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
        }

    private fun input(file: File): Pair<NextSleepInput, kotlinx.serialization.json.JsonElement?> {
        val fixture = Json.parseToJsonElement(file.readText()).jsonObject
        val input = fixture["input"]!!.jsonObject

        fun text(
            e: kotlinx.serialization.json.JsonObject,
            key: String,
        ): String? = e[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val events =
            input["events"]!!.jsonArray.map { element ->
                val e = element.jsonObject
                StatEvent(
                    id = text(e, "id")!!,
                    type = text(e, "type")!!,
                    startedAt = Instant.parse(text(e, "started_at")),
                    endedAt = text(e, "ended_at")?.let(Instant::parse),
                    timezone = text(e, "timezone")!!,
                    amountMl = e["amount_ml"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
                    diaperKind = text(e, "diaper_kind"),
                    sleepKind = text(e, "sleep_kind"),
                )
            }
        return NextSleepInput(text(input, "timezone")!!, Instant.parse(text(input, "now")), events) to
            fixture["expected"]
    }

    @Test
    fun `every fixture is reproduced exactly and every outcome is covered`() {
        val files =
            File("../../testdata/predict")
                .listFiles { f ->
                    f.extension == "json"
                }.orEmpty()
                .sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        val statuses = mutableSetOf<String>()
        for (file in files) {
            val (input, expected) = input(file)
            val estimate = NextSleep.estimate(input)
            statuses += estimate.status
            assertEquals(file.name, expected, json.encodeToJsonElement(estimate))
        }
        assertEquals(
            setOf(
                NextSleep.STATUS_OK,
                NextSleep.STATUS_ASLEEP,
                NextSleep.STATUS_NIGHT_WAKING,
                NextSleep.STATUS_INSUFFICIENT,
            ),
            statuses,
        )
    }

    @Test
    fun `sub-second precision in the records does not change the estimate`() {
        val (input, expected) = input(File("../../testdata/predict/after_first_nap.json"))
        val jittered =
            input.copy(
                now = input.now.plusMillis(250),
                events =
                    input.events.map {
                        it.copy(
                            startedAt = it.startedAt.plusMillis(7),
                            endedAt = it.endedAt?.plusMillis(993),
                        )
                    },
            )
        // 993 ms pushes nothing over a second boundary once truncated; the result is identical.
        assertEquals(expected, json.encodeToJsonElement(NextSleep.estimate(jittered)))
    }

    @Test
    fun `an unknown zone is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            NextSleep.estimate(NextSleepInput("Mars/Olympus", Instant.parse("2026-10-08T12:00:00Z"), emptyList()))
        }
    }
}

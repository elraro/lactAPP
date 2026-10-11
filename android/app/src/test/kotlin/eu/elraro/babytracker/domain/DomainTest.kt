package eu.elraro.babytracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class DomainTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun event(
        type: EventType,
        start: String,
        end: String? = null,
        id: String = "$type-$start",
    ) = Event(
        id = id,
        babyId = "b",
        type = type,
        startedAt = Instant.parse(start),
        endedAt = end?.let(Instant::parse),
        timezone = "Europe/Madrid",
    )

    @Test
    fun `ids are version 7 UUIDs that sort by creation time`() {
        val earlier = Ids.uuidV7(Instant.parse("2026-10-08T12:00:00Z"))
        val later = Ids.uuidV7(Instant.parse("2026-10-08T12:00:00.001Z"))
        val parsed = UUID.fromString(earlier)
        assertEquals(7, parsed.version())
        assertEquals(2, parsed.variant())
        assertTrue(earlier < later)
        assertEquals(1000, (1..1000).map { Ids.uuidV7() }.toSet().size)
    }

    @Test
    fun `sleep is suggested as night between 19h and 6h local time`() {
        fun kind(
            utc: String,
            zone: ZoneId = madrid,
        ) = SleepRules.suggestKind(Instant.parse(utc), zone)
        // Madrid is UTC+2 in October.
        assertEquals(SleepKind.NAP, kind("2026-10-08T08:00:00Z")) // 10:00
        assertEquals(SleepKind.NAP, kind("2026-10-08T16:59:00Z")) // 18:59
        assertEquals(SleepKind.NIGHT, kind("2026-10-08T17:00:00Z")) // 19:00
        assertEquals(SleepKind.NIGHT, kind("2026-10-08T03:59:00Z")) // 05:59
        assertEquals(SleepKind.NAP, kind("2026-10-08T04:00:00Z")) // 06:00
        // The same instant is a nap for a family in another zone.
        assertEquals(SleepKind.NAP, kind("2026-10-08T17:00:00Z", ZoneId.of("America/New_York"))) // 13:00
    }

    @Test
    fun `validation mirrors the server rules`() {
        val bottle = event(EventType.BOTTLE, "2026-10-08T10:00:00Z")
        assertEquals(EventProblem.MISSING_DETAILS, bottle.problem())
        assertEquals(EventProblem.AMOUNT_OUT_OF_RANGE, bottle.copy(amountMl = 0).problem())
        assertEquals(EventProblem.AMOUNT_OUT_OF_RANGE, bottle.copy(amountMl = 1001).problem())
        assertNull(bottle.copy(amountMl = 1000).problem())
        assertEquals(EventProblem.MISSING_DETAILS, event(EventType.DIAPER, "2026-10-08T10:00:00Z").problem())
        assertEquals(EventProblem.MISSING_DETAILS, event(EventType.SLEEP, "2026-10-08T10:00:00Z").problem())
        val sleep =
            event(
                EventType.SLEEP,
                "2026-10-08T10:00:00Z",
                "2026-10-08T09:59:59Z",
            ).copy(sleepKind = SleepKind.NAP)
        assertEquals(EventProblem.END_BEFORE_START, sleep.problem())
        assertNull(sleep.copy(endedAt = sleep.startedAt).problem())
        assertEquals(EventProblem.NOTES_TOO_LONG, bottle.copy(amountMl = 90, notes = "ñ".repeat(4001)).problem())
        assertNull(bottle.copy(amountMl = 90, notes = "ñ".repeat(4000)).problem())
    }

    @Test
    fun `timeline shows a night across midnight as sleep on one day and wake on the next`() {
        val night = event(EventType.SLEEP, "2026-10-08T19:30:00Z", "2026-10-09T05:10:00Z", id = "night")
        val bottle = event(EventType.BOTTLE, "2026-10-09T05:30:00Z", id = "bottle")
        val nap = event(EventType.SLEEP, "2026-10-09T08:00:00Z", id = "open-nap")
        val events = listOf(night, bottle, nap)

        // 8 October in Madrid.
        val day1 = Timeline.build(events, Instant.parse("2026-10-07T22:00:00Z"), Instant.parse("2026-10-08T22:00:00Z"))
        assertEquals(listOf(TimelineKind.SLEEP_START to "night"), day1.map { it.kind to it.event.id })

        // 9 October: newest first; the open nap has no wake row.
        val day2 = Timeline.build(events, Instant.parse("2026-10-08T22:00:00Z"), Instant.parse("2026-10-09T22:00:00Z"))
        assertEquals(
            listOf(
                TimelineKind.SLEEP_START to "open-nap",
                TimelineKind.BOTTLE to "bottle",
                TimelineKind.SLEEP_END to "night",
            ),
            day2.map { it.kind to it.event.id },
        )

        val onlyBottles = Timeline.build(events, Instant.MIN, Instant.MAX, only = EventType.BOTTLE)
        assertEquals(listOf("bottle"), onlyBottles.map { it.event.id })
    }

    @Test
    fun `a zero-length sleep lists waking above falling asleep`() {
        val blink = event(EventType.SLEEP, "2026-10-08T10:00:00Z", "2026-10-08T10:00:00Z")
        val rows = Timeline.build(listOf(blink), Instant.MIN, Instant.MAX)
        assertEquals(listOf(TimelineKind.SLEEP_END, TimelineKind.SLEEP_START), rows.map { it.kind })
    }

    @Test
    fun `sleep recorded twice is flagged on both records`() {
        val now = Instant.parse("2026-10-08T20:00:00Z")
        val a = event(EventType.SLEEP, "2026-10-08T08:00:00Z", "2026-10-08T09:00:00Z", id = "a")
        val b = event(EventType.SLEEP, "2026-10-08T08:30:00Z", "2026-10-08T09:20:00Z", id = "b")
        val touching = event(EventType.SLEEP, "2026-10-08T09:20:00Z", "2026-10-08T09:40:00Z", id = "c")
        val open = event(EventType.SLEEP, "2026-10-08T18:00:00Z", id = "open")
        val inside = event(EventType.SLEEP, "2026-10-08T18:30:00Z", "2026-10-08T19:00:00Z", id = "inside")
        val bottle = event(EventType.BOTTLE, "2026-10-08T08:10:00Z", id = "bottle")

        assertEquals(setOf("a", "b"), Timeline.overlappingSleepIds(listOf(touching, b, bottle, a), now))
        // A session still in progress lasts until now.
        assertEquals(setOf("open", "inside"), Timeline.overlappingSleepIds(listOf(open, inside), now))
        assertTrue(Timeline.overlappingSleepIds(listOf(a, touching, open), now).isEmpty())
    }

    @Test
    fun `measurements need at least one realistic value`() {
        val m = event(EventType.MEASUREMENT, "2026-10-08T10:00:00Z")
        assertEquals(EventProblem.MISSING_DETAILS, m.problem())
        assertNull(m.copy(weightG = 5200).problem())
        assertNull(m.copy(heightMm = 580, headMm = 400).problem())
        assertEquals(EventProblem.MEASUREMENT_OUT_OF_RANGE, m.copy(weightG = 52).problem())
        assertEquals(EventProblem.MEASUREMENT_OUT_OF_RANGE, m.copy(weightG = 5200, heightMm = 58).problem())
        assertEquals(EventProblem.MEASUREMENT_OUT_OF_RANGE, m.copy(headMm = 4000).problem())
        assertEquals(
            EventProblem.MISSING_DETAILS,
            event(EventType.NOTE, "2026-10-08T10:00:00Z").copy(notes = " ").problem(),
        )
    }
}

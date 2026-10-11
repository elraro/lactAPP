package eu.elraro.babytracker.domain

import java.time.Instant

enum class TimelineKind {
    BOTTLE,
    DIAPER,
    SLEEP_START,
    SLEEP_END,
    MEASUREMENT,
    NOTE,
}

/** One line of the timeline. A sleep session produces two: falling asleep and waking up. */
data class TimelineRow(
    val at: Instant,
    val kind: TimelineKind,
    val event: Event,
)

object Timeline {
    /**
     * IDs of sleep sessions that overlap another one. The app prevents this on one phone;
     * it happens when two phones record the same sleep while offline (docs/SYNC.md §7).
     * An open session counts as lasting until [now].
     */
    fun overlappingSleepIds(
        events: List<Event>,
        now: Instant,
    ): Set<String> {
        val sleeps = events.filter { it.type == EventType.SLEEP }.sortedBy { it.startedAt }
        val ids = mutableSetOf<String>()
        for (i in sleeps.indices) {
            val end = sleeps[i].endedAt ?: now
            // Sorted by start: once a later session starts at or after this end, none overlaps.
            for (j in i + 1 until sleeps.size) {
                if (!sleeps[j].startedAt.isBefore(end)) break
                ids += sleeps[i].id
                ids += sleeps[j].id
            }
        }
        return ids
    }

    /**
     * Rows whose moment lies in [from, to), newest first. A night that crosses midnight
     * shows "sleep" on the day it began and "wake" on the day it ended.
     */
    fun build(
        events: List<Event>,
        from: Instant,
        to: Instant,
        only: EventType? = null,
    ): List<TimelineRow> =
        events
            .asSequence()
            .filter { only == null || it.type == only }
            .flatMap { event ->
                when (event.type) {
                    EventType.BOTTLE -> sequenceOf(TimelineRow(event.startedAt, TimelineKind.BOTTLE, event))
                    EventType.DIAPER -> sequenceOf(TimelineRow(event.startedAt, TimelineKind.DIAPER, event))
                    EventType.MEASUREMENT -> sequenceOf(TimelineRow(event.startedAt, TimelineKind.MEASUREMENT, event))
                    EventType.NOTE -> sequenceOf(TimelineRow(event.startedAt, TimelineKind.NOTE, event))
                    EventType.SLEEP ->
                        sequenceOf(
                            TimelineRow(event.startedAt, TimelineKind.SLEEP_START, event),
                            event.endedAt?.let { TimelineRow(it, TimelineKind.SLEEP_END, event) },
                        ).filterNotNull()
                }
            }.filter { !it.at.isBefore(from) && it.at.isBefore(to) }
            // Waking up sorts above falling asleep when both share an instant.
            .sortedWith(compareByDescending<TimelineRow> { it.at }.thenByDescending { it.kind.ordinal })
            .toList()
}

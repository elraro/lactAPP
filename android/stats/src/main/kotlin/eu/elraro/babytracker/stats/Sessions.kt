package eu.elraro.babytracker.stats

import java.time.Duration
import java.time.Instant

const val KIND_NAP = "nap"
const val KIND_NIGHT = "night"

/** Two night sessions separated by less than this are one night with a waking in between. */
private val NIGHT_WAKING_MAX: Duration = Duration.ofHours(4)

/** A longer gap between sessions is treated as missing data, not as time awake. */
private val WAKE_WINDOW_MAX: Duration = Duration.ofHours(16)

/** A normalized sleep interval; [end] is null while the baby is still asleep. */
data class Session(
    val start: Instant,
    val end: Instant?,
    val night: Boolean,
)

/** A period awake between two sleep sessions. */
data class WakeWindow(
    val start: Instant,
    val end: Instant,
    /** Seconds. */
    val duration: Int,
    /** 1 follows night sleep, 2 the first nap, and so on; 0 when the position is unknown. */
    val ordinal: Int,
    val beforeBedtime: Boolean,
    /** Seconds of the nap that ends the window; -1 if it ends with night sleep or an unfinished nap. */
    val nextNap: Int,
)

/** Events in the order both engines process them: by start, then by ID. */
internal fun List<StatEvent>.inOrder(): List<StatEvent> =
    sortedWith(compareBy<StatEvent> { it.startedAt }.thenBy { it.id })

/**
 * Sorts sleep events and merges those that overlap, so that no moment is counted twice
 * (docs/STATISTICS.md §6). Sessions that merely touch stay separate.
 */
fun normalize(events: List<StatEvent>): List<Session> {
    val out = mutableListOf<Session>()
    for (e in events.filter { it.type == "sleep" }.inOrder()) {
        val session = Session(e.startedAt, e.endedAt, e.sleepKind == KIND_NIGHT)
        val last = out.lastOrNull()
        val lastEnd = last?.end
        if (last != null && (lastEnd == null || session.start.isBefore(lastEnd))) {
            // Overlap: the union runs to the later end, or stays open if either is open.
            val end =
                if (lastEnd != null &&
                    (session.end == null || session.end.isAfter(lastEnd))
                ) {
                    session.end
                } else {
                    lastEnd
                }
            out[out.lastIndex] = last.copy(end = end, night = last.night || session.night)
        } else {
            out += session
        }
    }
    return out
}

/** The wake windows between consecutive sessions (docs/STATISTICS.md §7). */
fun wakeWindowsOf(sessions: List<Session>): List<WakeWindow> {
    val out = mutableListOf<WakeWindow>()
    var ordinal = 0
    for (i in 0 until sessions.size - 1) {
        val prev = sessions[i]
        val next = sessions[i + 1]
        val prevEnd = prev.end ?: break
        val gap = Duration.between(prevEnd, next.start)
        if (prev.night && next.night && gap < NIGHT_WAKING_MAX) continue // a waking during the night
        if (gap > WAKE_WINDOW_MAX) {
            ordinal = 0 // missing data: position unknown until the next night
            continue
        }
        if (prev.night) {
            ordinal = 1
        } else if (ordinal > 0) {
            ordinal++
        }
        val nextEnd = next.end
        out +=
            WakeWindow(
                start = prevEnd,
                end = next.start,
                duration = gap.seconds.toInt(),
                ordinal = ordinal,
                beforeBedtime = next.night,
                nextNap =
                    if (!next.night &&
                        nextEnd != null
                    ) {
                        Duration.between(next.start, nextEnd).seconds.toInt()
                    } else {
                        -1
                    },
            )
    }
    return out
}

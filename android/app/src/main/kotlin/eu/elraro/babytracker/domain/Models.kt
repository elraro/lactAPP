package eu.elraro.babytracker.domain

import java.time.Instant
import java.time.LocalDate

// The wire values are the ones stored by the server (docs/DATABASE.md).

enum class EventType(
    val wire: String,
) {
    BOTTLE("bottle"),
    DIAPER("diaper"),
    SLEEP("sleep"),
    MEASUREMENT("measurement"),
    NOTE("note"),
    ;

    companion object {
        fun fromWire(value: String): EventType? = entries.firstOrNull { it.wire == value }
    }
}

enum class MilkKind(
    val wire: String,
) {
    FORMULA("formula"),
    BREAST_MILK("breast_milk"),
    MIXED("mixed"),
    ;

    companion object {
        fun fromWire(value: String?): MilkKind? = entries.firstOrNull { it.wire == value }
    }
}

enum class DiaperKind(
    val wire: String,
) {
    WET("wet"),
    DIRTY("dirty"),
    BOTH("both"),
    ;

    companion object {
        fun fromWire(value: String?): DiaperKind? = entries.firstOrNull { it.wire == value }
    }
}

enum class SleepKind(
    val wire: String,
) {
    NAP("nap"),
    NIGHT("night"),
    ;

    companion object {
        fun fromWire(value: String?): SleepKind? = entries.firstOrNull { it.wire == value }
    }
}

data class Baby(
    val id: String,
    val name: String,
    val dateOfBirth: LocalDate,
    val notes: String? = null,
)

/**
 * One recorded event. Which optional fields are set depends on [type]; durations are
 * never stored, they are `endedAt - startedAt`.
 */
data class Event(
    val id: String,
    val babyId: String,
    val type: EventType,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    /** IANA zone of the device that recorded the event. */
    val timezone: String,
    val amountMl: Int? = null,
    val milkKind: MilkKind? = null,
    val diaperKind: DiaperKind? = null,
    val sleepKind: SleepKind? = null,
    val weightG: Int? = null,
    val heightMm: Int? = null,
    val headMm: Int? = null,
    val notes: String? = null,
    /** Server version this copy is based on; 0 until the server has seen the event. */
    val version: Int = 0,
) {
    val isOpenSleep: Boolean get() = type == EventType.SLEEP && endedAt == null
}

/** Why an event cannot be saved. Mirrors the server's validation (docs/DATABASE.md). */
enum class EventProblem {
    END_BEFORE_START,
    AMOUNT_OUT_OF_RANGE,
    NOTES_TOO_LONG,
    MISSING_DETAILS,
    MEASUREMENT_OUT_OF_RANGE,
}

const val MIN_AMOUNT_ML = 1
const val MAX_AMOUNT_ML = 1000
const val MAX_NOTES_LENGTH = 4000

// Plausible bounds for a child, the same as the server's (docs/DATABASE.md).
val WEIGHT_RANGE_G = 200..60_000
val HEIGHT_RANGE_MM = 200..2_000
val HEAD_RANGE_MM = 100..800

fun Event.problem(): EventProblem? =
    when {
        endedAt != null && endedAt.isBefore(startedAt) -> EventProblem.END_BEFORE_START
        notes != null && notes.codePointCount(0, notes.length) > MAX_NOTES_LENGTH -> EventProblem.NOTES_TOO_LONG
        else -> detailsProblem()
    }

/** What each type requires beyond time and notes. */
private fun Event.detailsProblem(): EventProblem? =
    when (type) {
        EventType.BOTTLE ->
            when (amountMl) {
                null -> EventProblem.MISSING_DETAILS
                !in MIN_AMOUNT_ML..MAX_AMOUNT_ML -> EventProblem.AMOUNT_OUT_OF_RANGE
                else -> null
            }
        EventType.DIAPER -> EventProblem.MISSING_DETAILS.takeIf { diaperKind == null }
        EventType.SLEEP -> EventProblem.MISSING_DETAILS.takeIf { sleepKind == null }
        EventType.NOTE -> EventProblem.MISSING_DETAILS.takeIf { notes.isNullOrBlank() }
        EventType.MEASUREMENT ->
            when {
                weightG == null && heightMm == null && headMm == null -> EventProblem.MISSING_DETAILS
                weightG != null && weightG !in WEIGHT_RANGE_G -> EventProblem.MEASUREMENT_OUT_OF_RANGE
                heightMm != null && heightMm !in HEIGHT_RANGE_MM -> EventProblem.MEASUREMENT_OUT_OF_RANGE
                headMm != null && headMm !in HEAD_RANGE_MM -> EventProblem.MEASUREMENT_OUT_OF_RANGE
                else -> null
            }
    }

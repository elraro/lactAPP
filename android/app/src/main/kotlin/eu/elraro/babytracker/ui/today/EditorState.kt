package eu.elraro.babytracker.ui.today

import androidx.annotation.StringRes
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.MAX_NOTES_LENGTH
import eu.elraro.babytracker.domain.MilkKind
import eu.elraro.babytracker.domain.SleepKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The form for creating or editing one event. Times are shown and edited as wall-clock
 * time in the device's current zone.
 */
data class EditorState(
    /** The event as it was when the form opened; for a new event, the blank it starts from. */
    val base: Event,
    val isNew: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val amount: String,
    val milkKind: MilkKind?,
    val diaperKind: DiaperKind,
    val sleepKind: SleepKind,
    val notes: String,
    val amountInvalid: Boolean = false,
    @param:StringRes val timeError: Int? = null,
) {
    val type: EventType get() = base.type

    fun withNotes(value: String) = copy(notes = value.take(MAX_NOTES_LENGTH))

    /** Digits only: the field is a whole number of millilitres. */
    fun withAmount(value: String) =
        copy(amount = value.filter(Char::isDigit).take(MAX_AMOUNT_DIGITS), amountInvalid = false)

    /** The event described by the form, or null if the amount is not a number. */
    fun toEvent(zone: ZoneId): Event? {
        val amountMl = if (type == EventType.BOTTLE) amount.toIntOrNull() ?: return null else null
        return base.copy(
            startedAt = start.atZone(zone).toInstant(),
            endedAt = end?.takeIf { type == EventType.SLEEP || type == EventType.BOTTLE }?.atZone(zone)?.toInstant(),
            amountMl = amountMl,
            milkKind = milkKind.takeIf { type == EventType.BOTTLE },
            diaperKind = diaperKind.takeIf { type == EventType.DIAPER },
            sleepKind = sleepKind.takeIf { type == EventType.SLEEP },
            notes = notes.trim().ifEmpty { null },
        )
    }

    companion object {
        private const val MAX_AMOUNT_DIGITS = 4

        fun of(
            event: Event,
            isNew: Boolean,
            zone: ZoneId,
        ) = EditorState(
            base = event,
            isNew = isNew,
            // Minute precision is what the pickers can express.
            start =
                event.startedAt
                    .atZone(zone)
                    .toLocalDateTime()
                    .truncatedTo(ChronoUnit.MINUTES),
            end =
                event.endedAt
                    ?.atZone(zone)
                    ?.toLocalDateTime()
                    ?.truncatedTo(ChronoUnit.MINUTES),
            amount = event.amountMl?.toString().orEmpty(),
            milkKind = event.milkKind,
            diaperKind = event.diaperKind ?: DiaperKind.WET,
            sleepKind = event.sleepKind ?: SleepKind.NAP,
            notes = event.notes.orEmpty(),
        )
    }
}

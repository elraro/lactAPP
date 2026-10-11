package eu.elraro.babytracker.ui.today

import androidx.annotation.StringRes
import eu.elraro.babytracker.domain.DiaperKind
import eu.elraro.babytracker.domain.Event
import eu.elraro.babytracker.domain.EventType
import eu.elraro.babytracker.domain.MAX_NOTES_LENGTH
import eu.elraro.babytracker.domain.MilkKind
import eu.elraro.babytracker.domain.SleepKind
import java.math.RoundingMode
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
    /** Measurements as typed: kilograms and centimetres, with a comma or a point. */
    val weightKg: String = "",
    val heightCm: String = "",
    val headCm: String = "",
    val amountInvalid: Boolean = false,
    val measurementInvalid: Boolean = false,
    /** A note without text. */
    val notesMissing: Boolean = false,
    @param:StringRes val timeError: Int? = null,
) {
    val type: EventType get() = base.type

    fun withNotes(value: String) = copy(notes = value.take(MAX_NOTES_LENGTH), notesMissing = false)

    fun withWeight(value: String) = copy(weightKg = decimalInput(value), measurementInvalid = false)

    fun withHeight(value: String) = copy(heightCm = decimalInput(value), measurementInvalid = false)

    fun withHead(value: String) = copy(headCm = decimalInput(value), measurementInvalid = false)

    /** Digits only: the field is a whole number of millilitres. */
    fun withAmount(value: String) =
        copy(amount = value.filter(Char::isDigit).take(MAX_AMOUNT_DIGITS), amountInvalid = false)

    /** The event described by the form, or null if a number in it cannot be read. */
    fun toEvent(zone: ZoneId): Event? {
        val amountMl = if (type == EventType.BOTTLE) amount.toIntOrNull() ?: return null else null
        val measured = type == EventType.MEASUREMENT
        // A filled-in field that is not a number invalidates the form; an empty one is "not measured".
        val weight = if (measured) scaled(weightKg, GRAMS_PER_KG) ?: return null else UNMEASURED
        val height = if (measured) scaled(heightCm, MM_PER_CM) ?: return null else UNMEASURED
        val head = if (measured) scaled(headCm, MM_PER_CM) ?: return null else UNMEASURED
        return base.copy(
            weightG = weight.value,
            heightMm = height.value,
            headMm = head.value,
            startedAt = start.atZone(zone).toInstant(),
            endedAt = end?.takeIf { type == EventType.SLEEP || type == EventType.BOTTLE }?.atZone(zone)?.toInstant(),
            amountMl = amountMl,
            milkKind = milkKind.takeIf { type == EventType.BOTTLE },
            diaperKind = diaperKind.takeIf { type == EventType.DIAPER },
            sleepKind = sleepKind.takeIf { type == EventType.SLEEP },
            notes = notes.trim().ifEmpty { null },
        )
    }

    /** A measurement field: [value] is null when the field was left empty. */
    private class Measured(
        val value: Int?,
    )

    companion object {
        private const val MAX_AMOUNT_DIGITS = 4
        private const val MAX_DECIMAL_INPUT = 6
        private const val GRAMS_PER_KG = 1000
        private const val MM_PER_CM = 10
        private val UNMEASURED = Measured(null)

        private fun decimalInput(value: String) =
            value.filter { it.isDigit() || it == ',' || it == '.' }.take(MAX_DECIMAL_INPUT)

        /** "5,25" kg → 5250 g. Null when the text is not a number; an empty field is unmeasured. */
        private fun scaled(
            text: String,
            factor: Int,
        ): Measured? {
            if (text.isBlank()) return UNMEASURED
            val number = text.replace(',', '.').toBigDecimalOrNull() ?: return null
            return Measured(number.multiply(factor.toBigDecimal()).setScale(0, RoundingMode.HALF_UP).toInt())
        }

        private fun unscaled(
            value: Int?,
            factor: Int,
        ): String =
            value
                ?.toBigDecimal()
                ?.divide(factor.toBigDecimal())
                ?.stripTrailingZeros()
                ?.toPlainString()
                .orEmpty()

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
            weightKg = unscaled(event.weightG, GRAMS_PER_KG),
            heightCm = unscaled(event.heightMm, MM_PER_CM),
            headCm = unscaled(event.headMm, MM_PER_CM),
        )
    }
}

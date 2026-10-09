package eu.elraro.babytracker.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.elraro.babytracker.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date and time text for the selected language, the device's 12/24-hour setting and the
 * device's current zone (docs/ARCHITECTURE.md §7–8). Patterns come from the platform's
 * locale data, never from a fixed pattern.
 */
class Formatters(
    locale: Locale,
    val is24Hour: Boolean,
    val zone: ZoneId,
) {
    private val time =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"), locale)
    private val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMM"), locale)

    fun time(instant: Instant): String = time.format(instant.atZone(zone))

    fun time(local: LocalDateTime): String = time.format(local)

    fun date(day: LocalDate): String = date.format(day)

    fun dateTime(instant: Instant): String {
        val local = instant.atZone(zone)
        return "${date.format(local)}, ${time.format(local)}"
    }

    /** Time alone for [instant] on [day]; date and time when it falls on another day. */
    fun timeOn(
        instant: Instant,
        day: LocalDate,
    ): String {
        val local = instant.atZone(zone)
        return if (local.toLocalDate() == day) time.format(local) else "${date.format(local)}, ${time.format(local)}"
    }
}

@Composable
fun rememberFormatters(): Formatters {
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    return remember(locale, is24Hour, zone) { Formatters(locale, is24Hour, zone) }
}

@Composable
fun durationText(duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    val hours = (minutes / MINUTES_PER_HOUR).toInt()
    val rest = (minutes % MINUTES_PER_HOUR).toInt()
    return if (hours > 0) {
        stringResource(R.string.duration_h_min, hours, rest)
    } else {
        stringResource(R.string.duration_min, rest)
    }
}

private const val MINUTES_PER_HOUR = 60

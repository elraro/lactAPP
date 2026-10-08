package eu.elraro.babytracker.domain

import java.time.Instant
import java.time.ZoneId

object SleepRules {
    // Sleep that starts in [19:00, 06:00) local time is suggested as night sleep
    // (decision D6); the user can always change it.
    private const val NIGHT_STARTS_AT_HOUR = 19
    private const val NIGHT_ENDS_AT_HOUR = 6

    fun suggestKind(
        start: Instant,
        zone: ZoneId,
    ): SleepKind {
        val hour = start.atZone(zone).hour
        return if (hour >= NIGHT_STARTS_AT_HOUR || hour < NIGHT_ENDS_AT_HOUR) SleepKind.NIGHT else SleepKind.NAP
    }
}

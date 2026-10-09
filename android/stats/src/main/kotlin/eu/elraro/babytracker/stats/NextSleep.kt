package eu.elraro.babytracker.stats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// The next-sleep estimate of docs/STATISTICS.md §9, step for step like the Go engine in
// server/internal/predict. Both must reproduce every fixture in testdata/predict.

data class NextSleepInput(
    val timezone: String,
    val now: Instant,
    val events: List<StatEvent>,
)

@Serializable
data class NextSleepBasis(
    /** Position in the day of the window in progress; 0 = unknown. */
    @SerialName("wake_window_ordinal") val wakeWindowOrdinal: Int,
    @SerialName("sample_size") val sampleSize: Int,
    /** same_ordinal | time_of_day | all */
    @SerialName("sample_rule") val sampleRule: String,
    @SerialName("awake_since") val awakeSince: String,
    @SerialName("p25_s") val p25S: Int,
    @SerialName("p50_s") val p50S: Int,
    @SerialName("p75_s") val p75S: Int,
    @SerialName("lookback_days") val lookbackDays: Int,
)

/** The estimate. Everything but [status] is null unless the status is "ok". Instants are ISO-8601 UTC. */
@Serializable
data class NextSleepEstimate(
    /** ok | asleep | night_waking | insufficient_data */
    val status: String,
    val kind: String? = null,
    @SerialName("window_start") val windowStart: String? = null,
    @SerialName("window_end") val windowEnd: String? = null,
    val point: String? = null,
    /** high | moderate | low */
    val confidence: String? = null,
    val overdue: Boolean? = null,
    val basis: NextSleepBasis? = null,
)

object NextSleep {
    const val STATUS_OK = "ok"
    const val STATUS_ASLEEP = "asleep"
    const val STATUS_NIGHT_WAKING = "night_waking"
    const val STATUS_INSUFFICIENT = "insufficient_data"
    const val LOOKBACK_DAYS = 30

    private const val RULE_SAME_ORDINAL = "same_ordinal"
    private const val RULE_TIME_OF_DAY = "time_of_day"
    private const val RULE_ALL = "all"

    private const val MIN_PRIMARY_SAMPLE = 5
    private const val MIN_FALLBACK_SAMPLE = 3
    private const val TIME_OF_DAY_RADIUS = 90
    private const val MIN_WINDOW_SECONDS = 20 * 60
    private const val BEDTIME_LEAD = 45
    private const val MIN_BEDTIMES = 3
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MINUTES_PER_HOUR = 60
    private const val DEFAULT_NIGHT_FROM = 19 * 60
    private const val DEFAULT_NIGHT_TO = 6 * 60
    private const val NIGHT_WAKING_FROM = 20 * 60
    private const val NIGHT_WAKING_TO = 5 * 60
    private const val HIGH_SAMPLE = 10
    private const val HIGH_SPREAD = 25
    private const val MODERATE_SPREAD = 45
    private const val PERCENT = 100
    private val BEDTIME_LOOKBACK: Duration = Duration.ofDays(14)
    private val NIGHT_WAKING_GAP: Duration = Duration.ofHours(4)

    private class Sample(
        val duration: Int,
        val weight: Int,
        val end: Instant,
    )

    /**
     * The estimate as of [NextSleepInput.now].
     * @throws IllegalArgumentException for an unknown zone.
     */
    fun estimate(input: NextSleepInput): NextSleepEstimate {
        val zone =
            try {
                ZoneId.of(input.timezone)
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("predict: invalid timezone", e)
            }
        val now = input.now.truncatedTo(ChronoUnit.SECONDS)
        val sessions = sessionsAsOf(input.events, now)
        val last = sessions.lastOrNull() ?: return NextSleepEstimate(STATUS_INSUFFICIENT)
        val awakeSince = last.end ?: return NextSleepEstimate(STATUS_ASLEEP)
        val wokeAt = minuteOfDay(awakeSince, zone)
        if (last.night && (wokeAt >= NIGHT_WAKING_FROM || wokeAt < NIGHT_WAKING_TO)) {
            return NextSleepEstimate(STATUS_NIGHT_WAKING)
        }

        // The window in progress, obtained by pretending the baby falls asleep right now.
        val all = wakeWindowsOf(sessions + Session(now, null, night = false))
        val current =
            all.lastOrNull()?.takeIf { it.start == awakeSince } ?: return NextSleepEstimate(STATUS_INSUFFICIENT)
        val cutoff = now.minus(Duration.ofDays(LOOKBACK_DAYS.toLong()))
        val history = all.dropLast(1).filter { !it.end.isBefore(cutoff) }

        val (rule, chosen) = choose(history, current, zone) ?: return NextSleepEstimate(STATUS_INSUFFICIENT)
        val samples = chosen.map { Sample(it.duration, recencyWeight(Duration.between(it.end, now)), it.end) }
        val p25 = weightedPercentile(samples, QUARTILE_1)
        val p50 = weightedPercentile(samples, MEDIAN)
        val p75 = weightedPercentile(samples, QUARTILE_3)

        var start = awakeSince.plusSeconds(p25.toLong())
        var end = awakeSince.plusSeconds(p75.toLong())
        if (p75 - p25 < MIN_WINDOW_SECONDS) {
            // Too narrow to be useful (or to be honest): centre a 20-minute window on the band.
            val mid = awakeSince.plusSeconds(((p25 + p75) / 2).toLong())
            start = mid.minusSeconds(MIN_WINDOW_SECONDS / 2L)
            end = mid.plusSeconds(MIN_WINDOW_SECONDS / 2L)
        }
        val point = awakeSince.plusSeconds(p50.toLong())
        return NextSleepEstimate(
            status = STATUS_OK,
            kind = if (isNight(point, sessions, now, zone)) KIND_NIGHT else KIND_NAP,
            windowStart = start.toString(),
            windowEnd = end.toString(),
            point = point.toString(),
            confidence = confidenceOf(samples.size, p25, p50, p75, rule),
            overdue = now.isAfter(end),
            basis =
                NextSleepBasis(
                    current.ordinal,
                    samples.size,
                    rule,
                    awakeSince.toString(),
                    p25,
                    p50,
                    p75,
                    LOOKBACK_DAYS,
                ),
        )
    }

    /**
     * Sessions as they were known at [now]: events that start later are ignored, and a
     * session that ends later is still in progress. Whole seconds.
     */
    private fun sessionsAsOf(
        events: List<StatEvent>,
        now: Instant,
    ): List<Session> =
        normalize(
            events.filter { !it.startedAt.isAfter(now) }.map { e ->
                e.copy(
                    startedAt = e.startedAt.truncatedTo(ChronoUnit.SECONDS),
                    endedAt = e.endedAt?.takeIf { !it.isAfter(now) }?.truncatedTo(ChronoUnit.SECONDS),
                )
            },
        )

    private fun minuteOfDay(
        instant: Instant,
        zone: ZoneId,
    ): Int {
        val local = instant.atZone(zone)
        return local.hour * MINUTES_PER_HOUR + local.minute
    }

    /** The past wake windows most comparable to the one in progress, with the rule that chose them. */
    private fun choose(
        history: List<WakeWindow>,
        current: WakeWindow,
        zone: ZoneId,
    ): Pair<String, List<WakeWindow>>? {
        if (current.ordinal > 0) {
            val same = history.filter { it.ordinal == current.ordinal }
            if (same.size >= MIN_PRIMARY_SAMPLE) return RULE_SAME_ORDINAL to same
        }
        val at = minuteOfDay(current.start, zone)
        val near =
            history.filter {
                val diff = abs(minuteOfDay(it.start, zone) - at)
                minOf(diff, MINUTES_PER_DAY - diff) <= TIME_OF_DAY_RADIUS
            }
        return when {
            near.size >= MIN_PRIMARY_SAMPLE -> RULE_TIME_OF_DAY to near
            history.size >= MIN_FALLBACK_SAMPLE -> RULE_ALL to history
            else -> null
        }
    }

    /** Recent days count more, with small integer weights. */
    @Suppress("MagicNumber") // the table of docs/STATISTICS.md §9, step 4
    private fun recencyWeight(age: Duration): Int =
        when (age.toDays()) {
            in Long.MIN_VALUE..3 -> 4
            in 4..7 -> 3
            in 8..14 -> 2
            else -> 1
        }

    /** The smallest duration whose cumulative weight reaches [pct] percent of the total. */
    private fun weightedPercentile(
        samples: List<Sample>,
        pct: Int,
    ): Int {
        val sorted = samples.sortedWith(compareBy<Sample> { it.duration }.thenBy { it.end })
        val total = sorted.sumOf { it.weight }
        var cumulative = 0
        for (s in sorted) {
            cumulative += s.weight
            if (cumulative * PERCENT >= pct * total) return s.duration
        }
        return sorted.last().duration
    }

    /** Whether sleep starting at [point] is the night rather than a nap. */
    private fun isNight(
        point: Instant,
        sessions: List<Session>,
        now: Instant,
        zone: ZoneId,
    ): Boolean {
        // Bedtimes: the first session of each night in the last two weeks. Times after
        // midnight count as late evening so that the median is taken on a continuous scale.
        val bedtimes =
            sessions
                .filterIndexed { i, s ->
                    val previous = sessions.getOrNull(i - 1)
                    val previousEnd = previous?.end
                    val afterWaking =
                        previous != null &&
                            previous.night &&
                            previousEnd != null &&
                            Duration.between(previousEnd, s.start) < NIGHT_WAKING_GAP
                    s.night && !s.start.isBefore(now.minus(BEDTIME_LOOKBACK)) && !afterWaking
                }.map {
                    minuteOfDay(it.start, zone).let { m ->
                        if (m <
                            MINUTES_PER_DAY / 2
                        ) {
                            m + MINUTES_PER_DAY
                        } else {
                            m
                        }
                    }
                }.sorted()
        val at = minuteOfDay(point, zone)
        return when {
            at < DEFAULT_NIGHT_TO -> true
            bedtimes.size < MIN_BEDTIMES -> at >= DEFAULT_NIGHT_FROM
            // Lower median: an actual recorded bedtime, no rounding involved.
            else -> at >= bedtimes[(bedtimes.size - 1) / 2] - BEDTIME_LEAD
        }
    }

    private fun confidenceOf(
        n: Int,
        p25: Int,
        p50: Int,
        p75: Int,
        rule: String,
    ): String {
        if (p50 <= 0) return "low"
        val spread = (p75 - p25) * PERCENT / p50 // interquartile range as a percentage of the median
        return when {
            n >= HIGH_SAMPLE && spread <= HIGH_SPREAD && rule == RULE_SAME_ORDINAL -> "high"
            n >= MIN_PRIMARY_SAMPLE && spread <= MODERATE_SPREAD -> "moderate"
            else -> "low"
        }
    }

    private const val QUARTILE_1 = 25
    private const val MEDIAN = 50
    private const val QUARTILE_3 = 75
}

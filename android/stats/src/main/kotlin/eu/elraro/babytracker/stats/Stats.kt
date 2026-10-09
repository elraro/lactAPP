package eu.elraro.babytracker.stats

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs

// Minimum sample sizes (docs/STATISTICS.md). Below them a metric is reported as null.
private const val MIN_DAYS = 3
private const val MIN_FEEDS = 5
private const val MIN_NAPS = 5
private const val MIN_WAKE_WINDOWS = 5
private const val MIN_CORRELATION = 12
private const val MIN_START_BUCKET = 4
private const val MIN_CHANGE_SAMPLE = 5

private const val MAX_NAP_ORDINAL = 5
private const val HISTOGRAM_BIN_S = 15 * 60
private const val START_BUCKET_MIN = 30
private const val CHANGE_PERIOD_DAYS = 7
private const val MAX_DAYS = 366
private const val HOURS_PER_DAY = 24
private const val MINUTES_PER_HOUR = 60
private const val TERCILES = 3

object Stats {
    /**
     * Calculates every statistic for the range described by [input].
     * @throws IllegalArgumentException for an unknown zone, a malformed date or a bad day count.
     */
    fun compute(input: StatsInput): StatsOutput = Computation(input).output()
}

/** One local calendar day of the range. */
private class Day(
    val date: String,
    val from: Instant,
    val to: Instant,
    val complete: Boolean,
)

/** A finished nap that starts inside the range. */
private class Nap(
    val duration: Int,
    val dayIndex: Int,
    /** Position among the naps that start on the same day, from 1. */
    val ordinal: Int,
    /** Minutes after local midnight in the reporting zone. */
    val minuteOfDay: Int,
)

private fun zoneOrNull(id: String): ZoneId? =
    try {
        ZoneId.of(id)
    } catch (_: DateTimeException) {
        null
    }

private class Computation(
    private val input: StatsInput,
) {
    private val zone: ZoneId = requireNotNull(zoneOrNull(input.timezone)) { "stats: invalid timezone" }
    private val days: List<Day>
    private val from: Instant
    private val to: Instant
    private val events = input.events.inOrder()
    private val sessions = normalize(events)
    private val windows = wakeWindowsOf(sessions)

    init {
        val end =
            try {
                LocalDate.parse(input.end)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("stats: invalid end date", e)
            }
        require(input.days in 1..MAX_DAYS) { "stats: days out of range" }
        // Calendar arithmetic in the zone: days are 23 or 25 hours long when clocks change.
        days =
            (input.days - 1 downTo 0).map { back ->
                val date = end.minusDays(back.toLong())
                val dayFrom = date.atStartOfDay(zone).toInstant()
                val dayTo = date.plusDays(1).atStartOfDay(zone).toInstant()
                Day(date.toString(), dayFrom, dayTo, complete = !input.now.isBefore(dayTo))
            }
        from = days.first().from
        to = days.last().to
    }

    fun output() =
        StatsOutput(
            range = Range(input.days, days.first().date, days.last().date, input.timezone, days.count { it.complete }),
            feeding = feeding(),
            diapers = diapers(),
            sleep = sleep(),
            wakeWindows = wakeWindows(),
            patterns = patterns(),
        )

    /** Index of the range day containing [t], or -1. */
    private fun dayIndex(t: Instant): Int = days.indexOfFirst { !t.isBefore(it.from) && t.isBefore(it.to) }

    /** Hour of day where the event was recorded, so that travel does not smear time-of-day statistics. */
    private fun localHour(e: StatEvent): Int = e.startedAt.atZone(zoneOrNull(e.timezone) ?: zone).hour

    private fun feeding(): Feeding {
        val dayMl = IntArray(days.size)
        val dayFeeds = IntArray(days.size)
        val hourMl = IntArray(HOURS_PER_DAY)
        val hourFeeds = IntArray(HOURS_PER_DAY)
        val amounts = mutableListOf<Int>()
        val intervals = mutableListOf<Int>()
        var previous: Instant? = null
        for (e in events) {
            val i = dayIndex(e.startedAt)
            val ml = e.amountMl
            if (e.type == "bottle" && ml != null && i >= 0) {
                dayMl[i] += ml
                dayFeeds[i]++
                amounts += ml
                val hour = localHour(e)
                hourFeeds[hour]++
                hourMl[hour] += ml
                previous?.let { intervals += (e.startedAt.epochSecond - it.epochSecond).toInt() }
                previous = e.startedAt
            }
        }
        val counted = days.indices.filter { days[it].complete && dayFeeds[it] > 0 }
        return Feeding(
            todayMl = dayMl.last(),
            todayFeeds = dayFeeds.last(),
            totalMl = dayMl.sum(),
            feeds = dayFeeds.sum(),
            daily = days.indices.map { FeedingDay(days[it].date, dayMl[it], dayFeeds[it]) },
            avgDailyMl = meanValue(counted.map { dayMl[it] }, MIN_DAYS),
            feedsPerDayX10 = meanValue(counted.map { dayFeeds[it] * TENTHS }, MIN_DAYS),
            amountMl = distribution(amounts, MIN_FEEDS),
            intervalS = distribution(intervals, MIN_FEEDS),
            byHour = (0 until HOURS_PER_DAY).map { FeedingHour(it, hourFeeds[it], hourMl[it]) },
        )
    }

    private fun diapers(): Diapers {
        val perDay = Array(days.size) { mutableListOf<String>() }
        for (e in events) {
            val i = dayIndex(e.startedAt)
            val kind = e.diaperKind
            if (e.type == "diaper" && kind != null && i >= 0) perDay[i] += kind
        }

        fun counts(kinds: List<String>) =
            DiaperCounts(
                kinds.size,
                kinds.count { it == "wet" },
                kinds.count { it == "dirty" },
                kinds.count {
                    it ==
                        "both"
                },
            )
        val daily = perDay.map(::counts)
        return Diapers(
            today = daily.last(),
            total = counts(perDay.flatMap { it }),
            daily =
                days.indices.map {
                    DiaperDay(
                        days[it].date,
                        daily[it].total,
                        daily[it].wet,
                        daily[it].dirty,
                        daily[it].both,
                    )
                },
            perDayX10 =
                meanValue(
                    days.indices.filter { days[it].complete && daily[it].total > 0 }.map {
                        daily[it].total *
                            TENTHS
                    },
                    MIN_DAYS,
                ),
        )
    }

    /** Seconds of [session] that fall inside [day]; a session still in progress counts up to now. */
    private fun secondsIn(
        session: Session,
        day: Day,
    ): Int {
        val start = maxOf(session.start, day.from)
        val end = minOf(session.end ?: input.now, day.to)
        return if (end.isAfter(start)) (end.epochSecond - start.epochSecond).toInt() else 0
    }

    private fun naps(): List<Nap> {
        val perDay = IntArray(days.size)
        val out = mutableListOf<Nap>()
        for (s in sessions) {
            val i = dayIndex(s.start)
            if (s.night || i < 0) continue
            perDay[i]++
            val end = s.end ?: continue
            val local = s.start.atZone(zone)
            out +=
                Nap(
                    (end.epochSecond - s.start.epochSecond).toInt(),
                    i,
                    perDay[i],
                    local.hour * MINUTES_PER_HOUR + local.minute,
                )
        }
        return out
    }

    private fun sleepDays(): List<SleepDay> =
        days.map { day ->
            var night = 0
            var daytime = 0
            var naps = 0
            for (s in sessions) {
                val seconds = secondsIn(s, day)
                if (s.night) night += seconds else daytime += seconds
                if (!s.night && !s.start.isBefore(day.from) && s.start.isBefore(day.to)) naps++
            }
            SleepDay(day.date, night + daytime, night, daytime, naps)
        }

    private fun sleep(): Sleep {
        val daily = sleepDays()
        val counted = days.indices.filter { days[it].complete && daily[it].totalS > 0 }.map { daily[it] }
        val naps = naps()
        return Sleep(
            today = daily.last(),
            daily = daily,
            avgTotalS = meanValue(counted.map { it.totalS }, MIN_DAYS),
            avgNightS = meanValue(counted.map { it.nightS }, MIN_DAYS),
            avgDayS = meanValue(counted.map { it.dayS }, MIN_DAYS),
            napsPerDayX10 = meanValue(counted.map { it.naps * TENTHS }, MIN_DAYS),
            napDurationS = distribution(naps.map { it.duration }, MIN_NAPS),
            napStarts =
                (1..MAX_NAP_ORDINAL).mapNotNull { ordinal ->
                    val starts = naps.filter { it.ordinal == ordinal }.map { it.minuteOfDay }
                    val mean = meanValue(starts, MIN_NAPS)
                    if (starts.isEmpty()) null else NapStart(ordinal, mean.value, mean.sampleSize)
                },
        )
    }

    /** Wake windows whose end (the moment of falling asleep) lies in [[start], [end]). */
    private fun windowsEndingIn(
        start: Instant,
        end: Instant,
    ): List<WakeWindow> = windows.filter { !it.end.isBefore(start) && it.end.isBefore(end) }

    private fun wakeWindows(): WakeWindows {
        val inRange = windowsEndingIn(from, to)
        val maxOrdinal = inRange.maxOfOrNull { it.ordinal } ?: 0
        val maxBin = inRange.maxOfOrNull { it.duration / HISTOGRAM_BIN_S } ?: -1
        return WakeWindows(
            overall = distribution(inRange.map { it.duration }, MIN_WAKE_WINDOWS),
            byOrdinal =
                (1..maxOrdinal).mapNotNull { ordinal ->
                    val durations = inRange.filter { it.ordinal == ordinal }.map { it.duration }
                    if (durations.isEmpty()) return@mapNotNull null
                    val d = distribution(durations, MIN_WAKE_WINDOWS)
                    OrdinalWindows(ordinal, d.median, d.p25, d.p75, d.min, d.max, d.mean, d.sampleSize)
                },
            histogram =
                (0..maxBin).map { bin ->
                    HistogramBin(bin * HISTOGRAM_BIN_S, inRange.count { it.duration / HISTOGRAM_BIN_S == bin })
                },
        )
    }

    private fun patterns() =
        Patterns(
            wakeWindowVsNap = correlation(),
            napByStartTime =
                naps()
                    .groupBy { it.minuteOfDay / START_BUCKET_MIN * START_BUCKET_MIN }
                    .filterValues { it.size >= MIN_START_BUCKET }
                    .toSortedMap()
                    .map { (minute, naps) -> StartBucket(minute, median(naps.map { it.duration }), naps.size) },
            changes = if (days.size >= 2 * CHANGE_PERIOD_DAYS) changes() else emptyList(),
        )

    /** Wake window length against the duration of the nap that follows it. */
    private fun correlation(): Correlation {
        // Stable sort by wake length, like the Go engine.
        val pairs = windowsEndingIn(from, to).filter { it.nextNap >= 0 }.sortedBy { it.duration }
        if (pairs.size < MIN_CORRELATION) return Correlation(null, null, pairs.size, emptyList())
        val wakes = pairs.map { it.duration }
        val napsAfter = pairs.map { it.nextNap }
        val rho = spearmanX1000(wakes, napsAfter)
        val n = pairs.size
        return Correlation(
            spearmanX1000 = rho,
            strength = rho?.let(::strength),
            sampleSize = n,
            terciles =
                (0 until TERCILES).map { t ->
                    val lo = t * n / TERCILES
                    val hi = (t + 1) * n / TERCILES
                    Tercile(wakes[hi - 1], median(napsAfter.subList(lo, hi)), hi - lo)
                },
        )
    }

    /** Compares the last seven days of the range with the seven before them. */
    private fun changes(): List<Change> {
        val n = days.size
        val recent = n - CHANGE_PERIOD_DAYS until n
        val previous = n - 2 * CHANGE_PERIOD_DAYS until n - CHANGE_PERIOD_DAYS
        val feeding = feeding()
        val sleep = sleepDays()
        val naps = naps()

        fun change(
            metric: String,
            minSample: Int,
            summarize: (List<Int>) -> Int,
            values: (IntRange) -> List<Int>,
        ): Change {
            val r = values(recent)
            val p = values(previous)
            val enough = r.size >= minSample && p.size >= minSample
            return Change(
                metric,
                if (enough) summarize(r) else null,
                if (enough) summarize(p) else null,
                r.size,
                p.size,
            )
        }
        return listOf(
            change("wake_window_median_s", MIN_CHANGE_SAMPLE, ::median) { period ->
                windowsEndingIn(days[period.first].from, days[period.last].to).map { it.duration }
            },
            change("nap_duration_median_s", MIN_CHANGE_SAMPLE, ::median) { period ->
                naps.filter { it.dayIndex in period }.map { it.duration }
            },
            change("daily_intake_avg_ml", MIN_DAYS, ::meanHalfUp) { period ->
                period.filter { days[it].complete && feeding.daily[it].feeds > 0 }.map { feeding.daily[it].totalMl }
            },
            change("daily_sleep_avg_s", MIN_DAYS, ::meanHalfUp) { period ->
                period.filter { days[it].complete && sleep[it].totalS > 0 }.map { sleep[it].totalS }
            },
        )
    }
}

private const val TENTHS = 10
private const val WEAK = 200
private const val MODERATE = 400
private const val STRONG = 600

private fun strength(rhoX1000: Int): String =
    when (abs(rhoX1000)) {
        in 0 until WEAK -> "none"
        in WEAK until MODERATE -> "weak"
        in MODERATE until STRONG -> "moderate"
        else -> "strong"
    }

package eu.elraro.babytracker.stats

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// The Kotlin statistics engine. It implements docs/STATISTICS.md exactly like the Go engine
// in server/internal/stats; both must reproduce every fixture in testdata/stats. Property
// names and JSON names mirror the Go types one to one.

/** The subset of a recorded event that statistics need. */
data class StatEvent(
    val id: String,
    val type: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val timezone: String,
    val amountMl: Int? = null,
    val diaperKind: String? = null,
    val sleepKind: String? = null,
)

/**
 * A range of [days] local calendar days ending on [end] (ISO date, inclusive) in the
 * reporting [timezone]. [events] should reach back at least one day before the range and
 * must not contain deleted events.
 */
data class StatsInput(
    val timezone: String,
    val end: String,
    val days: Int,
    val now: Instant,
    val events: List<StatEvent>,
)

/** A number with the size of its sample; [value] is null below the metric's minimum. */
@Serializable
data class Value(
    val value: Int?,
    @SerialName("sample_size") val sampleSize: Int,
)

@Serializable
data class Distribution(
    val median: Int? = null,
    val p25: Int? = null,
    val p75: Int? = null,
    val min: Int? = null,
    val max: Int? = null,
    val mean: Int? = null,
    @SerialName("sample_size") val sampleSize: Int,
)

@Serializable
data class StatsOutput(
    val range: Range,
    val feeding: Feeding,
    val diapers: Diapers,
    val sleep: Sleep,
    @SerialName("wake_windows") val wakeWindows: WakeWindows,
    val patterns: Patterns,
)

@Serializable
data class Range(
    val days: Int,
    val start: String,
    val end: String,
    val timezone: String,
    @SerialName("complete_days") val completeDays: Int,
)

@Serializable
data class Feeding(
    @SerialName("today_ml") val todayMl: Int,
    @SerialName("today_feeds") val todayFeeds: Int,
    @SerialName("total_ml") val totalMl: Int,
    val feeds: Int,
    val daily: List<FeedingDay>,
    @SerialName("avg_daily_ml") val avgDailyMl: Value,
    @SerialName("feeds_per_day_x10") val feedsPerDayX10: Value,
    @SerialName("amount_ml") val amountMl: Distribution,
    @SerialName("interval_s") val intervalS: Distribution,
    @SerialName("by_hour") val byHour: List<FeedingHour>,
)

@Serializable
data class FeedingDay(
    val date: String,
    @SerialName("total_ml") val totalMl: Int,
    val feeds: Int,
)

@Serializable
data class FeedingHour(
    val hour: Int,
    val feeds: Int,
    @SerialName("total_ml") val totalMl: Int,
)

@Serializable
data class DiaperCounts(
    val total: Int = 0,
    val wet: Int = 0,
    val dirty: Int = 0,
    val both: Int = 0,
)

@Serializable
data class DiaperDay(
    val date: String,
    val total: Int,
    val wet: Int,
    val dirty: Int,
    val both: Int,
)

@Serializable
data class Diapers(
    val today: DiaperCounts,
    val total: DiaperCounts,
    val daily: List<DiaperDay>,
    @SerialName("per_day_x10") val perDayX10: Value,
)

@Serializable
data class SleepDay(
    val date: String,
    @SerialName("total_s") val totalS: Int,
    @SerialName("night_s") val nightS: Int,
    @SerialName("day_s") val dayS: Int,
    val naps: Int,
)

@Serializable
data class NapStart(
    val ordinal: Int,
    @SerialName("mean_minute_of_day") val meanMinuteOfDay: Int?,
    @SerialName("sample_size") val sampleSize: Int,
)

@Serializable
data class Sleep(
    val today: SleepDay,
    val daily: List<SleepDay>,
    @SerialName("avg_total_s") val avgTotalS: Value,
    @SerialName("avg_night_s") val avgNightS: Value,
    @SerialName("avg_day_s") val avgDayS: Value,
    @SerialName("naps_per_day_x10") val napsPerDayX10: Value,
    @SerialName("nap_duration_s") val napDurationS: Distribution,
    @SerialName("nap_starts") val napStarts: List<NapStart>,
)

@Serializable
data class OrdinalWindows(
    val ordinal: Int,
    val median: Int?,
    val p25: Int?,
    val p75: Int?,
    val min: Int?,
    val max: Int?,
    val mean: Int?,
    @SerialName("sample_size") val sampleSize: Int,
)

@Serializable
data class HistogramBin(
    @SerialName("from_s") val fromS: Int,
    val count: Int,
)

@Serializable
data class WakeWindows(
    val overall: Distribution,
    @SerialName("by_ordinal") val byOrdinal: List<OrdinalWindows>,
    val histogram: List<HistogramBin>,
)

@Serializable
data class Tercile(
    @SerialName("max_wake_s") val maxWakeS: Int,
    @SerialName("median_nap_s") val medianNapS: Int,
    @SerialName("sample_size") val sampleSize: Int,
)

@Serializable
data class Correlation(
    @SerialName("spearman_x1000") val spearmanX1000: Int?,
    /** none | weak | moderate | strong */
    val strength: String?,
    @SerialName("sample_size") val sampleSize: Int,
    val terciles: List<Tercile>,
)

@Serializable
data class StartBucket(
    @SerialName("from_minute") val fromMinute: Int,
    @SerialName("median_s") val medianS: Int,
    @SerialName("sample_size") val sampleSize: Int,
)

@Serializable
data class Change(
    val metric: String,
    val recent: Int?,
    val previous: Int?,
    @SerialName("sample_recent") val sampleRecent: Int,
    @SerialName("sample_previous") val samplePrevious: Int,
)

@Serializable
data class Patterns(
    @SerialName("wake_window_vs_nap") val wakeWindowVsNap: Correlation,
    @SerialName("nap_by_start_time") val napByStartTime: List<StartBucket>,
    val changes: List<Change>,
)

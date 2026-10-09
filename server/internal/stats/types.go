// Package stats computes the deterministic statistics of docs/STATISTICS.md.
//
// Everything here is a pure function of its input: no database, no system clock, no
// randomness. The Kotlin engine in android/stats implements the same definitions, and
// both must produce the expected output of every fixture in testdata/stats.
package stats

import "time"

// Event is the subset of a recorded event that statistics need.
type Event struct {
	ID         string     `json:"id"`
	Type       string     `json:"type"`
	StartedAt  time.Time  `json:"started_at"`
	EndedAt    *time.Time `json:"ended_at"`
	Timezone   string     `json:"timezone"`
	AmountMl   *int       `json:"amount_ml"`
	DiaperKind *string    `json:"diaper_kind"`
	SleepKind  *string    `json:"sleep_kind"`
}

// Input selects a range of Days local calendar days ending on End (inclusive) in the
// reporting zone. Events should reach back at least one day before the range so that
// sleep and wake windows that straddle its start are complete; deleted events must
// already be removed.
type Input struct {
	Timezone string    `json:"timezone"`
	End      string    `json:"end"`
	Days     int       `json:"days"`
	Now      time.Time `json:"now"`
	Events   []Event   `json:"events"`
}

// Value is a number together with the size of the sample it was computed from.
// Value is nil when the sample is smaller than the metric's minimum.
type Value struct {
	Value      *int `json:"value"`
	SampleSize int  `json:"sample_size"`
}

// Distribution summarises a sample; every field except SampleSize is nil below the minimum.
type Distribution struct {
	Median     *int `json:"median"`
	P25        *int `json:"p25"`
	P75        *int `json:"p75"`
	Min        *int `json:"min"`
	Max        *int `json:"max"`
	Mean       *int `json:"mean"`
	SampleSize int  `json:"sample_size"`
}

type Output struct {
	Range       Range       `json:"range"`
	Feeding     Feeding     `json:"feeding"`
	Diapers     Diapers     `json:"diapers"`
	Sleep       Sleep       `json:"sleep"`
	WakeWindows WakeWindows `json:"wake_windows"`
	Patterns    Patterns    `json:"patterns"`
}

type Range struct {
	Days     int    `json:"days"`
	Start    string `json:"start"`
	End      string `json:"end"`
	Timezone string `json:"timezone"`
	// CompleteDays excludes the last day while it is still in progress at Now.
	CompleteDays int `json:"complete_days"`
}

type Feeding struct {
	TodayMl    int          `json:"today_ml"`
	TodayFeeds int          `json:"today_feeds"`
	TotalMl    int          `json:"total_ml"`
	Feeds      int          `json:"feeds"`
	Daily      []FeedingDay `json:"daily"`
	// AvgDailyMl and FeedsPerDayX10 are means over complete days that have at least one feed.
	AvgDailyMl     Value `json:"avg_daily_ml"`
	FeedsPerDayX10 Value `json:"feeds_per_day_x10"`
	// AmountMl uses Mean and Median over every feed in the range.
	AmountMl Distribution `json:"amount_ml"`
	// IntervalS is the time between the starts of consecutive feeds.
	IntervalS Distribution  `json:"interval_s"`
	ByHour    []FeedingHour `json:"by_hour"`
}

type FeedingDay struct {
	Date    string `json:"date"`
	TotalMl int    `json:"total_ml"`
	Feeds   int    `json:"feeds"`
}

type FeedingHour struct {
	Hour    int `json:"hour"`
	Feeds   int `json:"feeds"`
	TotalMl int `json:"total_ml"`
}

type DiaperCounts struct {
	Total int `json:"total"`
	Wet   int `json:"wet"`
	Dirty int `json:"dirty"`
	Both  int `json:"both"`
}

type DiaperDay struct {
	Date string `json:"date"`
	DiaperCounts
}

type Diapers struct {
	Today     DiaperCounts `json:"today"`
	Total     DiaperCounts `json:"total"`
	Daily     []DiaperDay  `json:"daily"`
	PerDayX10 Value        `json:"per_day_x10"`
}

type SleepDay struct {
	Date   string `json:"date"`
	TotalS int    `json:"total_s"`
	NightS int    `json:"night_s"`
	DayS   int    `json:"day_s"`
	Naps   int    `json:"naps"`
}

type NapStart struct {
	Ordinal int `json:"ordinal"`
	// MeanMinuteOfDay is minutes after local midnight.
	MeanMinuteOfDay *int `json:"mean_minute_of_day"`
	SampleSize      int  `json:"sample_size"`
}

type Sleep struct {
	Today SleepDay   `json:"today"`
	Daily []SleepDay `json:"daily"`
	// Averages over complete days with any sleep recorded.
	AvgTotalS     Value        `json:"avg_total_s"`
	AvgNightS     Value        `json:"avg_night_s"`
	AvgDayS       Value        `json:"avg_day_s"`
	NapsPerDayX10 Value        `json:"naps_per_day_x10"`
	NapDurationS  Distribution `json:"nap_duration_s"`
	NapStarts     []NapStart   `json:"nap_starts"`
}

type OrdinalWindows struct {
	Ordinal int `json:"ordinal"`
	Distribution
}

type HistogramBin struct {
	FromS int `json:"from_s"`
	Count int `json:"count"`
}

type WakeWindows struct {
	Overall   Distribution     `json:"overall"`
	ByOrdinal []OrdinalWindows `json:"by_ordinal"`
	Histogram []HistogramBin   `json:"histogram"`
}

type Tercile struct {
	MaxWakeS   int `json:"max_wake_s"`
	MedianNapS int `json:"median_nap_s"`
	SampleSize int `json:"sample_size"`
}

type Correlation struct {
	// SpearmanX1000 is Spearman's rank correlation times 1000; nil below the minimum sample.
	SpearmanX1000 *int      `json:"spearman_x1000"`
	Strength      *string   `json:"strength"`
	SampleSize    int       `json:"sample_size"`
	Terciles      []Tercile `json:"terciles"`
}

type StartBucket struct {
	FromMinute int `json:"from_minute"`
	MedianS    int `json:"median_s"`
	SampleSize int `json:"sample_size"`
}

type Change struct {
	Metric         string `json:"metric"`
	Recent         *int   `json:"recent"`
	Previous       *int   `json:"previous"`
	SampleRecent   int    `json:"sample_recent"`
	SamplePrevious int    `json:"sample_previous"`
}

type Patterns struct {
	WakeWindowVsNap Correlation   `json:"wake_window_vs_nap"`
	NapByStartTime  []StartBucket `json:"nap_by_start_time"`
	// Changes compares the last 7 days of the range with the 7 before; empty for shorter ranges.
	Changes []Change `json:"changes"`
}

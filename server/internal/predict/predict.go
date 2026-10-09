// Package predict estimates when the baby will next fall asleep (docs/STATISTICS.md §9).
//
// The estimate is a deterministic function of the recorded sleep sessions: a weighted
// percentile band of past wake windows in the same position of the day. No model is
// trained and no language model is involved; the result carries everything it was
// computed from so that the app can show why. The Kotlin engine in android/stats
// implements the same steps and must reproduce every fixture in testdata/predict.
package predict

import (
	"errors"
	"sort"
	"time"

	"babytracker/internal/stats"
)

const (
	StatusOK           = "ok"
	StatusAsleep       = "asleep"
	StatusInsufficient = "insufficient_data"
	// StatusNightWaking: awake in the middle of the night. Daytime wake windows say nothing
	// about how long that lasts, so no estimate is given.
	StatusNightWaking = "night_waking"

	RuleSameOrdinal = "same_ordinal"
	RuleTimeOfDay   = "time_of_day"
	RuleAll         = "all"

	LookbackDays = 30

	minPrimarySample  = 5
	minFallbackSample = 3
	timeOfDayRadius   = 90 // minutes
	minWindowSeconds  = 20 * 60
	bedtimeLead       = 45 // minutes before the usual bedtime from which the next sleep is the night
	bedtimeLookback   = 14 * 24 * time.Hour
	minBedtimes       = 3
	minutesPerDay     = 24 * 60
	// Without enough recorded bedtimes, sleep starting from 19:00 or before 06:00 is night
	// (the same rule the app uses to suggest the kind of a new session).
	defaultNightFrom = 19 * 60
	defaultNightTo   = 6 * 60
	// Waking from night sleep between these local times is a night waking, not the morning.
	nightWakingFrom = 20 * 60
	nightWakingTo   = 5 * 60
)

type Input struct {
	Timezone string        `json:"timezone"`
	Now      time.Time     `json:"now"`
	Events   []stats.Event `json:"events"`
}

type Basis struct {
	// WakeWindowOrdinal is the position in the day of the window in progress; 0 = unknown.
	WakeWindowOrdinal int       `json:"wake_window_ordinal"`
	SampleSize        int       `json:"sample_size"`
	SampleRule        string    `json:"sample_rule"`
	AwakeSince        time.Time `json:"awake_since"`
	P25S              int       `json:"p25_s"`
	P50S              int       `json:"p50_s"`
	P75S              int       `json:"p75_s"`
	LookbackDays      int       `json:"lookback_days"`
}

// Output is the estimate. Everything but Status is nil unless Status is "ok".
type Output struct {
	Status      string     `json:"status"`
	Kind        *string    `json:"kind"`
	WindowStart *time.Time `json:"window_start"`
	WindowEnd   *time.Time `json:"window_end"`
	Point       *time.Time `json:"point"`
	Confidence  *string    `json:"confidence"`
	Overdue     *bool      `json:"overdue"`
	Basis       *Basis     `json:"basis"`
}

type sample struct {
	duration int
	weight   int
	end      time.Time
}

// NextSleep computes the estimate as of in.Now.
func NextSleep(in Input) (Output, error) {
	loc, err := time.LoadLocation(in.Timezone)
	if err != nil || in.Timezone == "" || in.Timezone == "Local" {
		return Output{}, errors.New("predict: invalid timezone")
	}
	now := in.Now.UTC().Truncate(time.Second)
	sessions := sessionsAsOf(in.Events, now)
	if len(sessions) == 0 {
		return Output{Status: StatusInsufficient}, nil
	}
	last := sessions[len(sessions)-1]
	if last.End == nil {
		return Output{Status: StatusAsleep}, nil
	}
	awakeSince := *last.End
	if at := minuteOfDay(awakeSince, loc); last.Night && (at >= nightWakingFrom || at < nightWakingTo) {
		return Output{Status: StatusNightWaking}, nil
	}

	// The window in progress, obtained by pretending the baby falls asleep right now.
	withNow := append(append([]stats.Session(nil), sessions...), stats.Session{Start: now})
	all := stats.WakeWindowsOf(withNow)
	if len(all) == 0 || !all[len(all)-1].Start.Equal(awakeSince) {
		// Awake for longer than a wake window can be: the records are stale.
		return Output{Status: StatusInsufficient}, nil
	}
	current := all[len(all)-1]
	past := all[:len(all)-1]

	cutoff := now.AddDate(0, 0, -LookbackDays)
	var history []stats.WakeWindow
	for _, w := range past {
		if !w.End.Before(cutoff) {
			history = append(history, w)
		}
	}

	rule, chosen := choose(history, current, loc)
	if rule == "" {
		return Output{Status: StatusInsufficient}, nil
	}
	samples := make([]sample, len(chosen))
	for i, w := range chosen {
		samples[i] = sample{duration: w.Duration, weight: recencyWeight(now.Sub(w.End)), end: w.End}
	}
	p25, p50, p75 := weightedPercentile(samples, 25), weightedPercentile(samples, 50), weightedPercentile(samples, 75)

	start := awakeSince.Add(time.Duration(p25) * time.Second)
	end := awakeSince.Add(time.Duration(p75) * time.Second)
	if p75-p25 < minWindowSeconds {
		// Too narrow to be useful (or to be honest): centre a 20-minute window on the band.
		mid := awakeSince.Add(time.Duration((p25+p75)/2) * time.Second)
		start, end = mid.Add(-minWindowSeconds/2*time.Second), mid.Add(minWindowSeconds/2*time.Second)
	}
	point := awakeSince.Add(time.Duration(p50) * time.Second)

	kind := stats.KindNap
	if isNight(point, sessions, now, loc) {
		kind = stats.KindNight
	}
	confidence := confidenceOf(len(samples), p25, p50, p75, rule)
	overdue := now.After(end)
	return Output{
		Status: StatusOK, Kind: &kind, WindowStart: &start, WindowEnd: &end, Point: &point,
		Confidence: &confidence, Overdue: &overdue,
		Basis: &Basis{
			WakeWindowOrdinal: current.Ordinal, SampleSize: len(samples), SampleRule: rule,
			AwakeSince: awakeSince, P25S: p25, P50S: p50, P75S: p75, LookbackDays: LookbackDays,
		},
	}, nil
}

// sessionsAsOf returns the normalized sessions as they were known at now: events that
// start later are ignored, and a session that ends later is still in progress.
func sessionsAsOf(events []stats.Event, now time.Time) []stats.Session {
	var known []stats.Event
	for _, e := range events {
		if e.StartedAt.After(now) {
			continue
		}
		// UTC, whole seconds: the output must not depend on the zone the times arrive in.
		e.StartedAt = e.StartedAt.UTC().Truncate(time.Second)
		if e.EndedAt != nil {
			if e.EndedAt.After(now) {
				e.EndedAt = nil
			} else {
				end := e.EndedAt.UTC().Truncate(time.Second)
				e.EndedAt = &end
			}
		}
		known = append(known, e)
	}
	return stats.Normalize(known)
}

func minuteOfDay(t time.Time, loc *time.Location) int {
	local := t.In(loc)
	return local.Hour()*60 + local.Minute()
}

// choose picks the past wake windows most comparable to the one in progress.
func choose(history []stats.WakeWindow, current stats.WakeWindow, loc *time.Location) (string, []stats.WakeWindow) {
	if current.Ordinal > 0 {
		var same []stats.WakeWindow
		for _, w := range history {
			if w.Ordinal == current.Ordinal {
				same = append(same, w)
			}
		}
		if len(same) >= minPrimarySample {
			return RuleSameOrdinal, same
		}
	}
	at := minuteOfDay(current.Start, loc)
	var near []stats.WakeWindow
	for _, w := range history {
		diff := minuteOfDay(w.Start, loc) - at
		if diff < 0 {
			diff = -diff
		}
		if diff > minutesPerDay-diff {
			diff = minutesPerDay - diff
		}
		if diff <= timeOfDayRadius {
			near = append(near, w)
		}
	}
	if len(near) >= minPrimarySample {
		return RuleTimeOfDay, near
	}
	if len(history) >= minFallbackSample {
		return RuleAll, history
	}
	return "", nil
}

// recencyWeight favours recent days with small integer weights.
func recencyWeight(age time.Duration) int {
	switch days := int(age / (24 * time.Hour)); {
	case days <= 3:
		return 4
	case days <= 7:
		return 3
	case days <= 14:
		return 2
	default:
		return 1
	}
}

// weightedPercentile returns the smallest duration whose cumulative weight reaches pct
// percent of the total. Integer arithmetic only.
func weightedPercentile(samples []sample, pct int) int {
	sorted := append([]sample(nil), samples...)
	sort.SliceStable(sorted, func(i, j int) bool {
		if sorted[i].duration != sorted[j].duration {
			return sorted[i].duration < sorted[j].duration
		}
		return sorted[i].end.Before(sorted[j].end)
	})
	total := 0
	for _, s := range sorted {
		total += s.weight
	}
	cumulative := 0
	for _, s := range sorted {
		cumulative += s.weight
		if cumulative*100 >= pct*total {
			return s.duration
		}
	}
	return sorted[len(sorted)-1].duration
}

// isNight reports whether sleep starting at point is the night rather than a nap.
func isNight(point time.Time, sessions []stats.Session, now time.Time, loc *time.Location) bool {
	// Bedtimes: the first session of each night in the last two weeks. Times after
	// midnight count as late evening so that the median is taken on a continuous scale.
	var bedtimes []int
	for i, s := range sessions {
		if !s.Night || s.Start.Before(now.Add(-bedtimeLookback)) {
			continue
		}
		if i > 0 && sessions[i-1].Night && sessions[i-1].End != nil && s.Start.Sub(*sessions[i-1].End) < 4*time.Hour {
			continue // back to sleep after a night waking, not a bedtime
		}
		m := minuteOfDay(s.Start, loc)
		if m < minutesPerDay/2 {
			m += minutesPerDay
		}
		bedtimes = append(bedtimes, m)
	}
	at := minuteOfDay(point, loc)
	if at < defaultNightTo {
		return true
	}
	if len(bedtimes) < minBedtimes {
		return at >= defaultNightFrom
	}
	sort.Ints(bedtimes)
	// Lower median: an actual recorded bedtime, no rounding involved.
	usual := bedtimes[(len(bedtimes)-1)/2]
	return at >= usual-bedtimeLead
}

func confidenceOf(n, p25, p50, p75 int, rule string) string {
	if p50 <= 0 {
		return "low"
	}
	spread := (p75 - p25) * 100 / p50 // interquartile range as a percentage of the median
	switch {
	case n >= 10 && spread <= 25 && rule == RuleSameOrdinal:
		return "high"
	case n >= 5 && spread <= 45:
		return "moderate"
	default:
		return "low"
	}
}

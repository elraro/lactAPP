package stats

import (
	"errors"
	"fmt"
	"sort"
	"time"
)

const dateLayout = "2006-01-02"

// Minimum sample sizes (docs/STATISTICS.md). Below them a metric is reported as nil.
const (
	minDays         = 3
	minFeeds        = 5
	minNaps         = 5
	minWakeWindows  = 5
	minCorrelation  = 12
	minStartBucket  = 4
	minChangeSample = 5

	maxNapOrdinal    = 5
	histogramBinS    = 15 * 60
	startBucketMin   = 30
	changePeriodDays = 7
)

// day is one local calendar day of the range.
type day struct {
	date     string
	from, to time.Time
	complete bool
}

type computation struct {
	loc      *time.Location
	now      time.Time
	days     []day
	from, to time.Time
	events   []Event
	sessions []Session
	windows  []WakeWindow
}

// Compute calculates every statistic for the range described by in.
func Compute(in Input) (Output, error) {
	loc, err := time.LoadLocation(in.Timezone)
	if err != nil || in.Timezone == "" || in.Timezone == "Local" {
		return Output{}, errors.New("stats: invalid timezone")
	}
	end, err := time.ParseInLocation(dateLayout, in.End, loc)
	if err != nil {
		return Output{}, errors.New("stats: invalid end date")
	}
	if in.Days < 1 || in.Days > 366 {
		return Output{}, errors.New("stats: days out of range")
	}

	c := computation{loc: loc, now: in.Now}
	for i := in.Days - 1; i >= 0; i-- {
		// Calendar arithmetic in the zone: days are 23 or 25 hours long when clocks change.
		from := time.Date(end.Year(), end.Month(), end.Day()-i, 0, 0, 0, 0, loc)
		to := time.Date(end.Year(), end.Month(), end.Day()-i+1, 0, 0, 0, 0, loc)
		c.days = append(c.days, day{date: from.Format(dateLayout), from: from, to: to, complete: !in.Now.Before(to)})
	}
	c.from, c.to = c.days[0].from, c.days[len(c.days)-1].to

	c.events = append([]Event(nil), in.Events...)
	sort.SliceStable(c.events, func(i, j int) bool {
		if !c.events[i].StartedAt.Equal(c.events[j].StartedAt) {
			return c.events[i].StartedAt.Before(c.events[j].StartedAt)
		}
		return c.events[i].ID < c.events[j].ID
	})
	c.sessions = Normalize(c.events)
	c.windows = WakeWindowsOf(c.sessions)

	complete := 0
	for _, d := range c.days {
		if d.complete {
			complete++
		}
	}
	return Output{
		Range: Range{
			Days: in.Days, Start: c.days[0].date, End: c.days[len(c.days)-1].date,
			Timezone: in.Timezone, CompleteDays: complete,
		},
		Feeding:     c.feeding(),
		Diapers:     c.diapers(),
		Sleep:       c.sleep(),
		WakeWindows: c.wakeWindows(),
		Patterns:    c.patterns(),
	}, nil
}

// dayIndex returns the index of the range day containing t, or -1.
func (c *computation) dayIndex(t time.Time) int {
	for i, d := range c.days {
		if !t.Before(d.from) && t.Before(d.to) {
			return i
		}
	}
	return -1
}

// localHour is the hour of day where the event was recorded; time-of-day statistics
// follow the event's own zone so that travel does not smear them.
func (c *computation) localHour(e Event) int {
	loc, err := time.LoadLocation(e.Timezone)
	if err != nil || e.Timezone == "" || e.Timezone == "Local" {
		loc = c.loc
	}
	return e.StartedAt.In(loc).Hour()
}

func (c *computation) feeding() Feeding {
	f := Feeding{Daily: make([]FeedingDay, len(c.days)), ByHour: make([]FeedingHour, 24)}
	for i, d := range c.days {
		f.Daily[i].Date = d.date
	}
	for h := range f.ByHour {
		f.ByHour[h].Hour = h
	}
	var amounts, intervals []int
	var previous *time.Time
	for _, e := range c.events {
		i := c.dayIndex(e.StartedAt)
		if e.Type != "bottle" || e.AmountMl == nil || i < 0 {
			continue
		}
		ml := *e.AmountMl
		f.Daily[i].TotalMl += ml
		f.Daily[i].Feeds++
		f.TotalMl += ml
		f.Feeds++
		amounts = append(amounts, ml)
		h := c.localHour(e)
		f.ByHour[h].Feeds++
		f.ByHour[h].TotalMl += ml
		if previous != nil {
			intervals = append(intervals, int(e.StartedAt.Sub(*previous)/time.Second))
		}
		start := e.StartedAt
		previous = &start
	}
	last := f.Daily[len(f.Daily)-1]
	f.TodayMl, f.TodayFeeds = last.TotalMl, last.Feeds

	var dayTotals, dayFeedsX10 []int
	for i, d := range c.days {
		if d.complete && f.Daily[i].Feeds > 0 {
			dayTotals = append(dayTotals, f.Daily[i].TotalMl)
			dayFeedsX10 = append(dayFeedsX10, f.Daily[i].Feeds*10)
		}
	}
	f.AvgDailyMl = meanValue(dayTotals, minDays)
	f.FeedsPerDayX10 = meanValue(dayFeedsX10, minDays)
	f.AmountMl = distribution(amounts, minFeeds)
	f.IntervalS = distribution(intervals, minFeeds)
	return f
}

func (c *computation) diapers() Diapers {
	d := Diapers{Daily: make([]DiaperDay, len(c.days))}
	for i, day := range c.days {
		d.Daily[i].Date = day.date
	}
	for _, e := range c.events {
		i := c.dayIndex(e.StartedAt)
		if e.Type != "diaper" || e.DiaperKind == nil || i < 0 {
			continue
		}
		for _, counts := range []*DiaperCounts{&d.Daily[i].DiaperCounts, &d.Total} {
			counts.Total++
			switch *e.DiaperKind {
			case "wet":
				counts.Wet++
			case "dirty":
				counts.Dirty++
			case "both":
				counts.Both++
			}
		}
	}
	d.Today = d.Daily[len(d.Daily)-1].DiaperCounts
	var perDayX10 []int
	for i, day := range c.days {
		if day.complete && d.Daily[i].Total > 0 {
			perDayX10 = append(perDayX10, d.Daily[i].Total*10)
		}
	}
	d.PerDayX10 = meanValue(perDayX10, minDays)
	return d
}

// overlapSeconds is the length of the intersection of [aFrom, aTo) and [bFrom, bTo).
func overlapSeconds(aFrom, aTo, bFrom, bTo time.Time) int {
	from, to := aFrom, aTo
	if bFrom.After(from) {
		from = bFrom
	}
	if bTo.Before(to) {
		to = bTo
	}
	if !to.After(from) {
		return 0
	}
	return int(to.Sub(from) / time.Second)
}

// nap is a finished nap that starts inside the range.
type nap struct {
	start     time.Time
	duration  int
	dayIndex  int
	ordinal   int // position among the naps that start on the same day, from 1
	minuteDay int // minutes after local midnight in the reporting zone
}

func (c *computation) naps() []nap {
	var out []nap
	perDay := make([]int, len(c.days))
	for _, s := range c.sessions {
		i := c.dayIndex(s.Start)
		if s.Night || i < 0 {
			continue
		}
		perDay[i]++
		if s.End == nil {
			continue
		}
		local := s.Start.In(c.loc)
		out = append(out, nap{
			start: s.Start, duration: int(s.End.Sub(s.Start) / time.Second), dayIndex: i,
			ordinal: perDay[i], minuteDay: local.Hour()*60 + local.Minute(),
		})
	}
	return out
}

func (c *computation) sleep() Sleep {
	s := Sleep{Daily: make([]SleepDay, len(c.days))}
	for i, d := range c.days {
		s.Daily[i].Date = d.date
		for _, session := range c.sessions {
			// A session still in progress counts up to now.
			end := c.now
			if session.End != nil {
				end = *session.End
			}
			seconds := overlapSeconds(session.Start, end, d.from, d.to)
			s.Daily[i].TotalS += seconds
			if session.Night {
				s.Daily[i].NightS += seconds
			} else {
				s.Daily[i].DayS += seconds
			}
			if !session.Night && !session.Start.Before(d.from) && session.Start.Before(d.to) {
				s.Daily[i].Naps++
			}
		}
	}
	s.Today = s.Daily[len(s.Daily)-1]

	var totals, nights, daytime, napsX10 []int
	for i, d := range c.days {
		if d.complete && s.Daily[i].TotalS > 0 {
			totals = append(totals, s.Daily[i].TotalS)
			nights = append(nights, s.Daily[i].NightS)
			daytime = append(daytime, s.Daily[i].DayS)
			napsX10 = append(napsX10, s.Daily[i].Naps*10)
		}
	}
	s.AvgTotalS = meanValue(totals, minDays)
	s.AvgNightS = meanValue(nights, minDays)
	s.AvgDayS = meanValue(daytime, minDays)
	s.NapsPerDayX10 = meanValue(napsX10, minDays)

	naps := c.naps()
	var durations []int
	starts := make([][]int, maxNapOrdinal+1)
	for _, n := range naps {
		durations = append(durations, n.duration)
		if n.ordinal <= maxNapOrdinal {
			starts[n.ordinal] = append(starts[n.ordinal], n.minuteDay)
		}
	}
	s.NapDurationS = distribution(durations, minNaps)
	s.NapStarts = []NapStart{}
	for ordinal := 1; ordinal <= maxNapOrdinal; ordinal++ {
		if len(starts[ordinal]) == 0 {
			continue
		}
		mean := meanValue(starts[ordinal], minNaps)
		s.NapStarts = append(s.NapStarts, NapStart{Ordinal: ordinal, MeanMinuteOfDay: mean.Value, SampleSize: mean.SampleSize})
	}
	return s
}

// windowsEndingIn returns the wake windows whose end (the moment of falling asleep) lies in [from, to).
func (c *computation) windowsEndingIn(from, to time.Time) []WakeWindow {
	var out []WakeWindow
	for _, w := range c.windows {
		if !w.End.Before(from) && w.End.Before(to) {
			out = append(out, w)
		}
	}
	return out
}

func (c *computation) wakeWindows() WakeWindows {
	windows := c.windowsEndingIn(c.from, c.to)
	var all []int
	byOrdinal := map[int][]int{}
	maxOrdinal := 0
	bins := map[int]int{}
	maxBin := -1
	for _, w := range windows {
		all = append(all, w.Duration)
		if w.Ordinal > 0 {
			byOrdinal[w.Ordinal] = append(byOrdinal[w.Ordinal], w.Duration)
			if w.Ordinal > maxOrdinal {
				maxOrdinal = w.Ordinal
			}
		}
		bin := w.Duration / histogramBinS
		bins[bin]++
		if bin > maxBin {
			maxBin = bin
		}
	}
	out := WakeWindows{Overall: distribution(all, minWakeWindows), ByOrdinal: []OrdinalWindows{}, Histogram: []HistogramBin{}}
	for ordinal := 1; ordinal <= maxOrdinal; ordinal++ {
		if len(byOrdinal[ordinal]) > 0 {
			out.ByOrdinal = append(out.ByOrdinal, OrdinalWindows{Ordinal: ordinal, Distribution: distribution(byOrdinal[ordinal], minWakeWindows)})
		}
	}
	for bin := 0; bin <= maxBin; bin++ {
		out.Histogram = append(out.Histogram, HistogramBin{FromS: bin * histogramBinS, Count: bins[bin]})
	}
	return out
}

func strength(rhoX1000 int) string {
	if rhoX1000 < 0 {
		rhoX1000 = -rhoX1000
	}
	switch {
	case rhoX1000 < 200:
		return "none"
	case rhoX1000 < 400:
		return "weak"
	case rhoX1000 < 600:
		return "moderate"
	default:
		return "strong"
	}
}

func (c *computation) patterns() Patterns {
	p := Patterns{NapByStartTime: []StartBucket{}, Changes: []Change{}}

	// Wake window length against the duration of the nap that follows it.
	type pair struct{ wake, nap int }
	var pairs []pair
	for _, w := range c.windowsEndingIn(c.from, c.to) {
		if w.NextNap >= 0 {
			pairs = append(pairs, pair{w.Duration, w.NextNap})
		}
	}
	p.WakeWindowVsNap = Correlation{SampleSize: len(pairs), Terciles: []Tercile{}}
	if len(pairs) >= minCorrelation {
		sort.SliceStable(pairs, func(i, j int) bool { return pairs[i].wake < pairs[j].wake })
		wakes, napsAfter := make([]int, len(pairs)), make([]int, len(pairs))
		for i, pr := range pairs {
			wakes[i], napsAfter[i] = pr.wake, pr.nap
		}
		if rho, ok := spearmanX1000(wakes, napsAfter); ok {
			label := strength(rho)
			p.WakeWindowVsNap.SpearmanX1000, p.WakeWindowVsNap.Strength = &rho, &label
		}
		n := len(pairs)
		for t := 0; t < 3; t++ {
			lo, hi := t*n/3, (t+1)*n/3
			p.WakeWindowVsNap.Terciles = append(p.WakeWindowVsNap.Terciles, Tercile{
				MaxWakeS: wakes[hi-1], MedianNapS: median(napsAfter[lo:hi]), SampleSize: hi - lo,
			})
		}
	}

	// Nap duration by the half hour in which the nap started.
	buckets := map[int][]int{}
	for _, n := range c.naps() {
		b := n.minuteDay / startBucketMin * startBucketMin
		buckets[b] = append(buckets[b], n.duration)
	}
	for b := 0; b < 24*60; b += startBucketMin {
		if len(buckets[b]) >= minStartBucket {
			p.NapByStartTime = append(p.NapByStartTime, StartBucket{FromMinute: b, MedianS: median(buckets[b]), SampleSize: len(buckets[b])})
		}
	}

	if len(c.days) >= 2*changePeriodDays {
		p.Changes = c.changes()
	}
	return p
}

// changes compares the last seven days of the range with the seven before them.
func (c *computation) changes() []Change {
	n := len(c.days)
	recent, previous := c.days[n-changePeriodDays:], c.days[n-2*changePeriodDays:n-changePeriodDays]
	feeding, sleep := c.feeding(), c.sleep()
	naps := c.naps()

	metric := func(name string, minSample int, values func(days []day, offset int) []int, summarize func([]int) int) Change {
		r, p := values(recent, n-changePeriodDays), values(previous, n-2*changePeriodDays)
		ch := Change{Metric: name, SampleRecent: len(r), SamplePrevious: len(p)}
		if len(r) >= minSample && len(p) >= minSample {
			ch.Recent, ch.Previous = intPtr(summarize(r)), intPtr(summarize(p))
		}
		return ch
	}
	windowDurations := func(days []day, _ int) []int {
		var out []int
		for _, w := range c.windowsEndingIn(days[0].from, days[len(days)-1].to) {
			out = append(out, w.Duration)
		}
		return out
	}
	napDurations := func(days []day, offset int) []int {
		var out []int
		for _, nap := range naps {
			if nap.dayIndex >= offset && nap.dayIndex < offset+len(days) {
				out = append(out, nap.duration)
			}
		}
		return out
	}
	dailyIntake := func(days []day, offset int) []int {
		var out []int
		for i, d := range days {
			if d.complete && feeding.Daily[offset+i].Feeds > 0 {
				out = append(out, feeding.Daily[offset+i].TotalMl)
			}
		}
		return out
	}
	dailySleep := func(days []day, offset int) []int {
		var out []int
		for i, d := range days {
			if d.complete && sleep.Daily[offset+i].TotalS > 0 {
				out = append(out, sleep.Daily[offset+i].TotalS)
			}
		}
		return out
	}
	return []Change{
		metric("wake_window_median_s", minChangeSample, windowDurations, median),
		metric("nap_duration_median_s", minChangeSample, napDurations, median),
		metric("daily_intake_avg_ml", minDays, dailyIntake, meanHalfUp),
		metric("daily_sleep_avg_s", minDays, dailySleep, meanHalfUp),
	}
}

// String makes fixture mismatches readable.
func (v Value) String() string {
	if v.Value == nil {
		return fmt.Sprintf("nil (n=%d)", v.SampleSize)
	}
	return fmt.Sprintf("%d (n=%d)", *v.Value, v.SampleSize)
}

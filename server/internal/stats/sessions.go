package stats

import (
	"sort"
	"time"
)

const (
	KindNap   = "nap"
	KindNight = "night"

	// Two night sessions separated by less than this are one night with a waking in between.
	nightWakingMax = 4 * time.Hour
	// A longer gap between sessions is treated as missing data, not as time awake.
	wakeWindowMax = 16 * time.Hour
)

// Session is a normalized sleep interval. End is nil while the baby is still asleep.
type Session struct {
	Start time.Time
	End   *time.Time
	Night bool
}

// Normalize sorts sleep events and merges those that overlap, so that no moment is
// counted twice (docs/STATISTICS.md §6). Sessions that merely touch stay separate.
func Normalize(events []Event) []Session {
	var sleeps []Event
	for _, e := range events {
		if e.Type == "sleep" {
			sleeps = append(sleeps, e)
		}
	}
	sort.SliceStable(sleeps, func(i, j int) bool {
		if !sleeps[i].StartedAt.Equal(sleeps[j].StartedAt) {
			return sleeps[i].StartedAt.Before(sleeps[j].StartedAt)
		}
		return sleeps[i].ID < sleeps[j].ID
	})
	var out []Session
	for _, e := range sleeps {
		s := Session{Start: e.StartedAt, End: e.EndedAt, Night: e.SleepKind != nil && *e.SleepKind == KindNight}
		if n := len(out); n > 0 {
			last := &out[n-1]
			if last.End == nil || s.Start.Before(*last.End) {
				// Overlap: the union runs to the later end, or stays open if either is open.
				if last.End != nil && (s.End == nil || s.End.After(*last.End)) {
					last.End = s.End
				}
				last.Night = last.Night || s.Night
				continue
			}
		}
		out = append(out, s)
	}
	return out
}

// WakeWindow is a period awake between two sleep sessions.
type WakeWindow struct {
	Start    time.Time
	End      time.Time
	Duration int // seconds
	// Ordinal is the position in the day: 1 follows night sleep, 2 the first nap, and so on.
	// 0 means unknown (the preceding night is not in the data).
	Ordinal int
	// BeforeBedtime is true when the window ends with night sleep.
	BeforeBedtime bool
	// NextNap is the duration in seconds of the nap that ends the window, or -1 when the
	// window ends with night sleep or with a nap that has not finished.
	NextNap int
}

// WakeWindows derives the wake windows between consecutive sessions (docs/STATISTICS.md §7).
func WakeWindowsOf(sessions []Session) []WakeWindow {
	var out []WakeWindow
	ordinal := 0
	for i := 0; i+1 < len(sessions); i++ {
		prev, next := sessions[i], sessions[i+1]
		if prev.End == nil {
			break
		}
		gap := next.Start.Sub(*prev.End)
		if prev.Night && next.Night && gap < nightWakingMax {
			continue // a waking during the night, not a wake window
		}
		if gap > wakeWindowMax {
			ordinal = 0 // missing data: the position in the day is unknown until the next night
			continue
		}
		switch {
		case prev.Night:
			ordinal = 1
		case ordinal > 0:
			ordinal++
		}
		w := WakeWindow{
			Start: *prev.End, End: next.Start, Duration: int(gap / time.Second),
			Ordinal: ordinal, BeforeBedtime: next.Night, NextNap: -1,
		}
		if !next.Night && next.End != nil {
			w.NextNap = int(next.End.Sub(next.Start) / time.Second)
		}
		out = append(out, w)
	}
	return out
}

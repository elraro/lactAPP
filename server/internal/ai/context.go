package ai

import (
	"time"

	"babytracker/internal/predict"
	"babytracker/internal/stats"
)

const (
	KindOverview = "overview"
	KindSleep    = "sleep"
	KindFeeding  = "feeding"
)

// ValidKind reports whether kind is one of the fixed analyses.
func ValidKind(kind string) bool {
	return kind == KindOverview || kind == KindSleep || kind == KindFeeding
}

// Context is everything a provider is told about a family: summaries produced by the
// statistics engine. By construction it has no place for a name, a date of birth, a
// note, an identifier or an individual event (docs/AI.md §4).
type Context struct {
	AnalysisKind string      `json:"analysis_kind"`
	Subject      Subject     `json:"subject"`
	Range        stats.Range `json:"range"`
	Observed     Observed    `json:"observed"`
	Calculated   Calculated  `json:"calculated"`
	// Prediction is nil for analyses that are not about sleep.
	Prediction *predict.Output `json:"prediction"`
}

type Subject struct {
	AgeDays int `json:"age_days"`
}

type Observed struct {
	Feeding *ObservedFeeding    `json:"feeding,omitempty"`
	Diapers *stats.DiaperCounts `json:"diapers,omitempty"`
	Sleep   *ObservedSleep      `json:"sleep,omitempty"`
}

type ObservedFeeding struct {
	Feeds   int `json:"feeds"`
	TotalMl int `json:"total_ml"`
}

type ObservedSleep struct {
	Naps        int `json:"naps"`
	WakeWindows int `json:"wake_windows"`
}

type Calculated struct {
	Feeding     *stats.Feeding     `json:"feeding,omitempty"`
	Diapers     *stats.Diapers     `json:"diapers,omitempty"`
	Sleep       *stats.Sleep       `json:"sleep,omitempty"`
	WakeWindows *stats.WakeWindows `json:"wake_windows,omitempty"`
	Patterns    *stats.Patterns    `json:"patterns,omitempty"`
}

// BuildContext selects from the engines' output what an analysis of the given kind needs.
// dateOfBirth is used only to derive the age in days as of now in loc.
func BuildContext(kind string, dateOfBirth, now time.Time, loc *time.Location, s stats.Output, next predict.Output) Context {
	today := now.In(loc)
	birth := time.Date(dateOfBirth.Year(), dateOfBirth.Month(), dateOfBirth.Day(), 0, 0, 0, 0, time.UTC)
	todayUTC := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, time.UTC)
	c := Context{
		AnalysisKind: kind,
		Subject:      Subject{AgeDays: max(0, int(todayUTC.Sub(birth)/(24*time.Hour)))},
		Range:        s.Range,
	}

	if kind == KindOverview || kind == KindFeeding {
		feeding := s.Feeding
		// Hours without feeds carry no information; leaving them out keeps the context small.
		feeding.ByHour = nil
		for _, h := range s.Feeding.ByHour {
			if h.Feeds > 0 {
				feeding.ByHour = append(feeding.ByHour, h)
			}
		}
		c.Observed.Feeding = &ObservedFeeding{Feeds: s.Feeding.Feeds, TotalMl: s.Feeding.TotalMl}
		c.Calculated.Feeding = &feeding
	}
	if kind == KindOverview {
		diapers := s.Diapers
		c.Observed.Diapers = &diapers.Total
		c.Calculated.Diapers = &diapers
	}
	if kind == KindOverview || kind == KindSleep {
		sleep, windows, patterns := s.Sleep, s.WakeWindows, s.Patterns
		naps := 0
		for _, d := range sleep.Daily {
			naps += d.Naps
		}
		// The histogram is a chart, not something to put into words.
		windows.Histogram = nil
		c.Observed.Sleep = &ObservedSleep{Naps: naps, WakeWindows: windows.Overall.SampleSize}
		c.Calculated.Sleep, c.Calculated.WakeWindows, c.Calculated.Patterns = &sleep, &windows, &patterns
		c.Prediction = &next
	}
	return c
}

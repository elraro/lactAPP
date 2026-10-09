package httpapi

import (
	"net/http"
	"strconv"
	"time"

	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/stats"
	"babytracker/internal/store"
)

const (
	defaultStatsDays = 7
	maxStatsDays     = 90
	// Events are loaded from this long before the range so that sleep and wake windows
	// that straddle its first day are complete.
	statsLookback = 48 * time.Hour
)

// statsEvents loads what the statistics and prediction engines need for [from, to).
func (s *server) statsEvents(r *http.Request, baby store.Baby, from, to time.Time) ([]stats.Event, error) {
	rows, err := s.q.ListEventsTouching(r.Context(), store.ListEventsTouchingParams{
		FamilyID: baby.FamilyID, BabyID: baby.ID, FromTime: from, ToTime: to,
	})
	if err != nil {
		return nil, err
	}
	events := make([]stats.Event, 0, len(rows))
	for _, e := range rows {
		ev := stats.Event{
			ID: e.ID.String(), Type: e.Type, StartedAt: e.StartedAt, EndedAt: e.EndedAt,
			Timezone: e.Timezone, DiaperKind: e.DiaperKind, SleepKind: e.SleepKind,
		}
		if e.AmountMl != nil {
			ml := int(*e.AmountMl)
			ev.AmountMl = &ml
		}
		events = append(events, ev)
	}
	return events, nil
}

// reportingZone is the `tz` parameter, or the family's zone when it is absent.
func (s *server) reportingZone(r *http.Request, c auth.Claims, fields map[string]string) (string, *time.Location) {
	tz := r.URL.Query().Get("tz")
	if tz == "" {
		family, err := s.q.GetFamily(r.Context(), c.FamilyID)
		if err == nil {
			tz = family.Timezone
		}
	}
	if !auth.ValidTimezone(tz) {
		fields["tz"] = "invalid"
		return "", time.UTC
	}
	loc, _ := time.LoadLocation(tz)
	return tz, loc
}

func (s *server) handleStats(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	baby, err := s.babyFromPath(r, c)
	if err != nil {
		return err
	}
	query := r.URL.Query()
	fields := map[string]string{}
	now := s.Now()
	tz, loc := s.reportingZone(r, c, fields)

	days := defaultStatsDays
	if v := query.Get("days"); v != "" {
		if days, err = strconv.Atoi(v); err != nil || days < 1 || days > maxStatsDays {
			fields["days"] = "out_of_range"
		}
	}
	end := now.In(loc).Format(time.DateOnly)
	if v := query.Get("end"); v != "" {
		if _, err := time.Parse(time.DateOnly, v); err != nil {
			fields["end"] = "invalid"
		}
		end = v
	}
	if len(fields) > 0 {
		return apperr.Validation(fields)
	}

	endDay, _ := time.ParseInLocation(time.DateOnly, end, loc)
	from := time.Date(endDay.Year(), endDay.Month(), endDay.Day()-days+1, 0, 0, 0, 0, loc)
	to := time.Date(endDay.Year(), endDay.Month(), endDay.Day()+1, 0, 0, 0, 0, loc)
	events, err := s.statsEvents(r, baby, from.Add(-statsLookback), to)
	if err != nil {
		return err
	}
	out, err := stats.Compute(stats.Input{Timezone: tz, End: end, Days: days, Now: now, Events: events})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, out)
	return nil
}

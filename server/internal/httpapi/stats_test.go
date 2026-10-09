package httpapi_test

import (
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"

	"babytracker/internal/httpapi"
)

func TestStatsEndpoint(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	// 8 October 2026, 22:00 in Madrid.
	e.clock.Advance(time.Date(2026, 10, 8, 20, 0, 0, 0, time.UTC).Sub(e.clock.Now()))
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	newID := func() string { return uuid.Must(uuid.NewV7()).String() }
	sleep := func(start, end, kind string) map[string]any {
		f := map[string]any{"baby_id": baby, "type": "sleep", "started_at": start, "timezone": "Europe/Madrid", "sleep_kind": kind}
		if end != "" {
			f["ended_at"] = end
		}
		return mutation("event", newID(), "upsert", 0, f)
	}
	deleted := newID()
	a.mustApply(
		// Started three days before the range: still found because it overlaps it.
		sleep("2026-10-07T19:30:00Z", "2026-10-08T05:10:00Z", "night"),
		mutation("event", newID(), "upsert", 0, bottle(baby, "2026-10-08T05:30:00Z", 150)),
		mutation("event", newID(), "upsert", 0, bottle(baby, "2026-10-08T09:00:00Z", 120)),
		mutation("event", deleted, "upsert", 0, bottle(baby, "2026-10-08T10:00:00Z", 999)),
		sleep("2026-10-08T07:45:00Z", "2026-10-08T08:35:00Z", "nap"),
		sleep("2026-10-08T19:20:00Z", "", "night"),
	)
	a.mustApply(mutation("event", deleted, "delete", 1, nil))

	status, body := a.do("GET", "/babies/"+baby+"/stats?days=1", nil)
	if status != http.StatusOK {
		t.Fatalf("stats: %d %v", status, body)
	}
	if r := body["range"].(map[string]any); r["start"] != "2026-10-08" || r["timezone"] != "Europe/Madrid" || r["complete_days"] != float64(0) {
		t.Errorf("range = %v", r)
	}
	if f := body["feeding"].(map[string]any); f["today_ml"] != float64(270) || f["today_feeds"] != float64(2) {
		t.Errorf("feeding = %v ml / %v feeds (deleted events must not count)", f["today_ml"], f["today_feeds"])
	}
	// Night 00:00–07:10 plus the open session 21:20 until "now" 22:00; one 50-minute nap.
	today := body["sleep"].(map[string]any)["today"].(map[string]any)
	if today["night_s"] != float64(25800+2400) || today["day_s"] != float64(3000) {
		t.Errorf("sleep today = %v", today)
	}

	// The same instant seen from New York is still 8 October there, but the night falls differently.
	status, body = a.do("GET", "/babies/"+baby+"/stats?days=1&tz=America/New_York&end=2026-10-08", nil)
	if status != http.StatusOK {
		t.Fatalf("stats in another zone: %d %v", status, body)
	}
	if r := body["range"].(map[string]any); r["timezone"] != "America/New_York" {
		t.Errorf("range = %v", r)
	}

	// Default range is seven days ending today in the family's zone.
	_, body = a.do("GET", "/babies/"+baby+"/stats", nil)
	if r := body["range"].(map[string]any); r["days"] != float64(7) || r["end"] != "2026-10-08" || r["start"] != "2026-10-02" {
		t.Errorf("default range = %v", r)
	}

	status, body = a.do("GET", "/babies/"+baby+"/stats?days=0&tz=Mars/Olympus&end=yesterday", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
	if fields := body["error"].(map[string]any)["fields"].(map[string]any); len(fields) != 3 {
		t.Errorf("fields = %v", fields)
	}
	status, body = a.do("GET", "/babies/"+baby+"/stats?days=91", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
}

func TestNextSleepEndpoint(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	// 07:20 in Madrid on 8 October 2026, twenty minutes after waking up.
	e.clock.Advance(time.Date(2026, 10, 8, 5, 20, 0, 0, time.UTC).Sub(e.clock.Now()))
	a := e.newFamily("a@example.com")
	baby := a.createBaby()

	status, body := a.do("GET", "/babies/"+baby+"/predictions/next-sleep", nil)
	if status != http.StatusOK || body["status"] != "insufficient_data" || body["window_start"] != nil {
		t.Fatalf("without records: %d %v", status, body)
	}

	// Six regular days: up at 07:00, a nap after 80–100 minutes, bedtime at 19:00.
	var muts []map[string]any
	sleep := func(start, end time.Time, kind string) {
		muts = append(muts, mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, map[string]any{
			"baby_id": baby, "type": "sleep", "sleep_kind": kind, "timezone": "Europe/Madrid",
			"started_at": start.Format(time.RFC3339), "ended_at": end.Format(time.RFC3339),
		}))
	}
	for i, window := range []int{80, 85, 90, 95, 100, 90} {
		wake := time.Date(2026, 10, 2+i, 5, 0, 0, 0, time.UTC)
		sleep(wake.Add(-12*time.Hour), wake, "night")
		nap := wake.Add(time.Duration(window) * time.Minute)
		sleep(nap, nap.Add(time.Hour), "nap")
	}
	lastWake := time.Date(2026, 10, 8, 5, 0, 0, 0, time.UTC)
	sleep(lastWake.Add(-12*time.Hour), lastWake, "night")
	a.mustApply(muts...)

	// The same case as predict.TestMorningEstimateByHand, through the database and HTTP.
	status, body = a.do("GET", "/babies/"+baby+"/predictions/next-sleep", nil)
	if status != http.StatusOK || body["status"] != "ok" {
		t.Fatalf("estimate: %d %v", status, body)
	}
	if body["window_start"] != "2026-10-08T06:20:00Z" || body["window_end"] != "2026-10-08T06:40:00Z" ||
		body["kind"] != "nap" || body["confidence"] != "moderate" || body["overdue"] != false {
		t.Errorf("estimate = %v", body)
	}
	basis := body["basis"].(map[string]any)
	if basis["sample_size"] != float64(6) || basis["sample_rule"] != "same_ordinal" || basis["p50_s"] != float64(5400) {
		t.Errorf("basis = %v", basis)
	}

	status, body = a.do("GET", "/babies/"+baby+"/predictions/next-sleep?tz=Nowhere", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
}

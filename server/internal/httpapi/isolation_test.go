package httpapi_test

import (
	"net/http"
	"testing"

	"github.com/google/uuid"

	"babytracker/internal/httpapi"
)

// TestFamiliesAreIsolated covers every endpoint that takes or returns family data.
// Add a case here whenever an endpoint is added (AGENTS.md §7).
func TestFamiliesAreIsolated(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	owner := e.newFamily("owner@example.com")
	baby := owner.createBaby()
	event := owner.createBottle(baby, 120)
	intruder := e.newFamily("intruder@example.com")

	t.Run("read endpoints return 404", func(t *testing.T) {
		for _, path := range []string{"/babies/" + baby, "/babies/" + baby + "/events", "/babies/" + baby + "/stats"} {
			status, body := intruder.do("GET", path, nil)
			expectError(t, status, body, http.StatusNotFound, "not_found")
		}
		_, body := intruder.do("GET", "/babies", nil)
		if n := len(body["babies"].([]any)); n != 0 {
			t.Errorf("intruder lists %d babies", n)
		}
		_, body = intruder.do("GET", "/family", nil)
		if n := len(body["members"].([]any)); n != 1 {
			t.Errorf("intruder sees %d members", n)
		}
	})

	t.Run("feed contains only own rows", func(t *testing.T) {
		changes, _ := intruder.pullAll("", "500")
		if len(changes) != 1 || changes[0].Entity != "family" || changes[0].Data["id"] != intruder.familyID {
			t.Errorf("intruder feed = %v", changes)
		}
	})

	t.Run("a cursor of another family is refused", func(t *testing.T) {
		_, cursor := owner.pullAll("", "500")
		status, body := intruder.do("GET", "/sync?cursor="+cursor, nil)
		expectError(t, status, body, http.StatusGone, "cursor_expired")
	})

	t.Run("push cannot touch another family", func(t *testing.T) {
		results := intruder.push(
			// New event attached to the other family's baby.
			mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, bottle(baby, "2026-10-08T09:00:00Z", 90)),
			// Overwrite, delete and restore of the other family's rows by ID.
			mutation("event", event, "upsert", 1, map[string]any{"amount_ml": 999}),
			mutation("event", event, "delete", 1, nil),
			mutation("baby", baby, "upsert", 1, map[string]any{"name": "Hijacked"}),
			mutation("baby", baby, "delete", 1, nil),
			// Full create that reuses an existing foreign ID.
			mutation("baby", baby, "upsert", 0, map[string]any{"name": "Clone", "date_of_birth": "2026-01-01"}),
		)
		want := []string{"not_found", "validation_failed", "not_found", "validation_failed", "not_found", "forbidden"}
		for i, r := range results {
			if r["status"] != "rejected" || r["error_code"] != want[i] {
				t.Errorf("mutation %d = %v, want rejected/%s", i, r, want[i])
			}
			if r["data"] != nil {
				t.Errorf("mutation %d leaked data: %v", i, r["data"])
			}
		}

		// Own baby, but the ID of a foreign event: must not overwrite it.
		ownBaby := intruder.createBaby()
		res := intruder.push(mutation("event", event, "upsert", 0, bottle(ownBaby, "2026-10-08T09:00:00Z", 999)))[0]
		if res["error_code"] != "forbidden" {
			t.Errorf("reusing a foreign event id = %v", res)
		}
	})

	t.Run("owner's data is unchanged", func(t *testing.T) {
		_, body := owner.do("GET", "/babies/"+baby, nil)
		if b := body["baby"].(map[string]any); b["name"] != "Vera" || b["version"] != float64(1) {
			t.Errorf("baby = %v", b)
		}
		_, body = owner.do("GET", "/babies/"+baby+"/events", nil)
		events := body["events"].([]any)
		if len(events) != 1 {
			t.Fatalf("owner has %d events, want 1", len(events))
		}
		if ev := events[0].(map[string]any); ev["amount_ml"] != float64(120) || ev["version"] != float64(1) {
			t.Errorf("event = %v", ev)
		}
	})

	t.Run("family_id in a mutation is rejected, never honoured", func(t *testing.T) {
		ownBaby := intruder.createBaby()
		fields := bottle(ownBaby, "2026-10-08T09:00:00Z", 90)
		fields["family_id"] = owner.familyID
		res := intruder.push(mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, fields))[0]
		if res["error_code"] != "validation_failed" {
			t.Errorf("mutation with family_id = %v", res)
		}
	})
}

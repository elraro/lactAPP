package httpapi_test

import (
	"context"
	"fmt"
	"net/http"
	"sync"
	"testing"

	"github.com/google/uuid"

	"babytracker/internal/httpapi"
)

func TestPushThenPull(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	eventID := uuid.Must(uuid.NewV7()).String()
	res := a.mustApply(mutation("event", eventID, "upsert", 0, bottle(baby, "2026-10-08T14:32:00+02:00", 120)))[0]

	ev := data(res)
	if ev["version"] != float64(1) || ev["amount_ml"] != float64(120) || ev["started_at"] != "2026-10-08T12:32:00Z" {
		t.Errorf("event = %v", ev)
	}
	if ev["created_by_user_id"] != a.userID || ev["created_by_device_id"] != a.deviceID {
		t.Errorf("attribution = %v / %v", ev["created_by_user_id"], ev["created_by_device_id"])
	}
	if res["merged"] != false {
		t.Error("a create must not be reported as merged")
	}

	// A second device of the family starts from nothing and receives everything.
	b := a.join("b@example.com")
	changes, cursor := b.pullAll("", "500")
	var entities []string
	for _, c := range changes {
		entities = append(entities, c.Entity)
	}
	if fmt.Sprint(entities) != "[family baby event]" {
		t.Fatalf("feed = %v", entities)
	}
	if changes[2].Data["id"] != eventID {
		t.Errorf("event id = %v", changes[2].Data["id"])
	}

	// Nothing new: empty page, same cursor.
	again, cursor2 := b.pullAll(cursor, "500")
	if len(again) != 0 || cursor2 != cursor {
		t.Errorf("idle pull returned %d changes, cursor changed: %v", len(again), cursor2 != cursor)
	}

	// Only the row changed afterwards is returned, once, with its latest state.
	a.mustApply(mutation("event", eventID, "upsert", 1, map[string]any{"amount_ml": 150}))
	a.mustApply(mutation("event", eventID, "upsert", 2, map[string]any{"notes": "spit up a little"}))
	delta, _ := b.pullAll(cursor, "500")
	if len(delta) != 1 || delta[0].Data["version"] != float64(3) || delta[0].Data["amount_ml"] != float64(150) {
		t.Errorf("delta = %v", delta)
	}
}

func TestPullPagingVisitsEveryRowExactlyOnce(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	want := map[string]bool{baby: true, a.familyID: true}
	for i := 0; i < 7; i++ {
		want[a.createBottle(baby, 60+i*10)] = true
	}
	for _, limit := range []string{"1", "2", "3", "500"} {
		changes, _ := a.pullAll("", limit)
		seen := map[string]int{}
		last := float64(0)
		for _, c := range changes {
			id := c.Data["id"].(string)
			seen[id]++
			if seq := c.Data["seq"].(float64); seq <= last {
				t.Errorf("limit %s: seq %v after %v", limit, seq, last)
			} else {
				last = seq
			}
		}
		if len(seen) != len(want) {
			t.Errorf("limit %s: saw %d rows, want %d", limit, len(seen), len(want))
		}
		for id, n := range seen {
			if n != 1 || !want[id] {
				t.Errorf("limit %s: row %s seen %d times", limit, id, n)
			}
		}
	}
}

func TestReplayedPushIsIdempotent(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	id := uuid.Must(uuid.NewV7()).String()
	create := mutation("event", id, "upsert", 0, bottle(baby, "2026-10-08T14:32:00Z", 120))
	update := mutation("event", id, "upsert", 1, map[string]any{"amount_ml": 90})

	first := a.mustApply(create, update)
	_, cursor := a.pullAll("", "500")

	// The response was "lost": the client sends the identical batch again, twice.
	for i := 0; i < 2; i++ {
		replay := a.mustApply(create, update)
		for j := range replay {
			if replay[j]["status"] != first[j]["status"] || replay[j]["merged"] != first[j]["merged"] {
				t.Errorf("replay %d result %d = %v, want %v", i, j, replay[j], first[j])
			}
		}
		if got := data(replay[1]); got["version"] != float64(2) || got["amount_ml"] != float64(90) {
			t.Errorf("replay changed the row: %v", got)
		}
	}
	if changes, _ := a.pullAll(cursor, "500"); len(changes) != 0 {
		t.Errorf("replay produced %d feed changes", len(changes))
	}
	_, body := a.do("GET", "/babies/"+baby+"/events", nil)
	if n := len(body["events"].([]any)); n != 1 {
		t.Errorf("%d events after replay, want 1", n)
	}

	// A replayed create must not undo a later edit by another device.
	b := a.join("b@example.com")
	b.mustApply(mutation("event", id, "upsert", 2, map[string]any{"amount_ml": 200}))
	a.mustApply(create)
	_, body = a.do("GET", "/babies/"+baby+"/events", nil)
	if got := body["events"].([]any)[0].(map[string]any)["amount_ml"]; got != float64(200) {
		t.Errorf("amount after stale replay = %v, want 200", got)
	}
}

func TestConcurrentEditsMergeByField(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	b := a.join("b@example.com")
	baby := a.createBaby()
	id := a.createBottle(baby, 120)

	// Both parents edit version 1 offline: different fields.
	ra := a.mustApply(mutation("event", id, "upsert", 1, map[string]any{"amount_ml": 150}))[0]
	rb := b.mustApply(mutation("event", id, "upsert", 1, map[string]any{"notes": "half asleep"}))[0]
	if ra["merged"] != false || rb["merged"] != true {
		t.Errorf("merged flags = %v, %v; want false, true", ra["merged"], rb["merged"])
	}
	got := data(rb)
	if got["amount_ml"] != float64(150) || got["notes"] != "half asleep" || got["version"] != float64(3) {
		t.Errorf("both edits should survive: %v", got)
	}
	if got["updated_by_user_id"] != b.userID || got["created_by_user_id"] != a.userID {
		t.Errorf("attribution = created %v updated %v", got["created_by_user_id"], got["updated_by_user_id"])
	}

	// Same field: the last one to reach the server wins.
	a.mustApply(mutation("event", id, "upsert", 3, map[string]any{"amount_ml": 100}))
	last := b.mustApply(mutation("event", id, "upsert", 3, map[string]any{"amount_ml": 110}))[0]
	if data(last)["amount_ml"] != float64(110) || last["merged"] != true {
		t.Errorf("last writer should win: %v", last)
	}
}

func TestDeleteWinsOverEdit(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	b := a.join("b@example.com")
	baby := a.createBaby()
	id := a.createBottle(baby, 120)

	del := a.mustApply(mutation("event", id, "delete", 1, nil))[0]
	if data(del)["deleted_at"] == nil {
		t.Fatalf("not tombstoned: %v", del)
	}
	edit := b.push(mutation("event", id, "upsert", 1, map[string]any{"amount_ml": 150}))[0]
	if edit["status"] != "rejected" || edit["error_code"] != "entity_deleted" {
		t.Errorf("edit of deleted event = %v", edit)
	}

	// Deleting again is a no-op that does not touch the feed.
	_, cursor := a.pullAll("", "500")
	again := b.mustApply(mutation("event", id, "delete", 1, nil))[0]
	if data(again)["version"] != float64(2) {
		t.Errorf("second delete bumped the version: %v", again)
	}
	if changes, _ := a.pullAll(cursor, "500"); len(changes) != 0 {
		t.Errorf("second delete produced %d changes", len(changes))
	}

	// The tombstone is delivered, and hidden from the read API unless asked for.
	changes, _ := b.pullAll("", "500")
	if last := changes[len(changes)-1]; last.Data["id"] != id || last.Data["deleted_at"] == nil {
		t.Errorf("tombstone missing from feed: %v", last)
	}
	_, body := a.do("GET", "/babies/"+baby+"/events", nil)
	if n := len(body["events"].([]any)); n != 0 {
		t.Errorf("deleted event listed (%d)", n)
	}
	_, body = a.do("GET", "/babies/"+baby+"/events?include_deleted=true", nil)
	if n := len(body["events"].([]any)); n != 1 {
		t.Errorf("include_deleted returned %d", n)
	}

	// Explicit restore brings it back, after which it can be edited again.
	restored := a.mustApply(mutation("event", id, "restore", 2, nil))[0]
	if data(restored)["deleted_at"] != nil || data(restored)["amount_ml"] != float64(120) {
		t.Errorf("restore = %v", restored)
	}
	b.mustApply(mutation("event", id, "upsert", 3, map[string]any{"amount_ml": 150}))
}

func TestRejectedMutationDoesNotBlockTheBatch(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	good1, bad, good2 := uuid.Must(uuid.NewV7()).String(), uuid.Must(uuid.NewV7()).String(), uuid.Must(uuid.NewV7()).String()
	badMutation := mutation("event", bad, "upsert", 0, bottle(baby, "2026-10-08T10:00:00Z", 5000))

	results := a.push(
		mutation("event", good1, "upsert", 0, bottle(baby, "2026-10-08T09:00:00Z", 90)),
		badMutation,
		mutation("event", uuid.NewString(), "delete", 1, nil),
		mutation("event", uuid.NewString(), "explode", 0, nil),
		mutation("gadget", uuid.NewString(), "upsert", 0, nil),
		mutation("event", good2, "upsert", 0, bottle(baby, "2026-10-08T11:00:00Z", 150)),
	)
	want := []string{"applied", "validation_failed", "not_found", "validation_failed", "validation_failed", "applied"}
	for i, r := range results {
		got := r["status"].(string)
		if got == "rejected" {
			got = r["error_code"].(string)
		}
		if got != want[i] {
			t.Errorf("result %d = %v, want %s", i, r, want[i])
		}
	}
	if fields := results[1]["fields"].(map[string]any); fields["amount_ml"] != "out_of_range" {
		t.Errorf("field errors = %v", fields)
	}

	// The verdict is permanent: a retry of the rejected mutation is not applied later.
	if again := a.push(badMutation)[0]; again["status"] != "rejected" || again["error_code"] != "validation_failed" {
		t.Errorf("replayed rejection = %v", again)
	}
	_, body := a.do("GET", "/babies/"+baby+"/events", nil)
	if n := len(body["events"].([]any)); n != 2 {
		t.Errorf("%d events stored, want 2", n)
	}
}

func TestEventTypesRoundTrip(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	common := func(extra map[string]any) map[string]any {
		m := map[string]any{"baby_id": baby, "started_at": "2026-10-08T21:30:00Z", "timezone": "Europe/Madrid"}
		for k, v := range extra {
			m[k] = v
		}
		return m
	}
	sleepID := uuid.Must(uuid.NewV7()).String()
	a.mustApply(
		mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, common(map[string]any{"type": "diaper", "diaper_kind": "both"})),
		mutation("event", sleepID, "upsert", 0, common(map[string]any{"type": "sleep", "sleep_kind": "night"})),
		mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, common(map[string]any{"type": "measurement", "weight_g": 5200, "height_mm": 580})),
		mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, common(map[string]any{"type": "note", "notes": "first laugh"})),
		mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, common(map[string]any{"type": "bottle", "amount_ml": 180, "milk_kind": "breast_milk"})),
	)

	// WAKE: the open session is closed the next morning, across midnight.
	woke := a.mustApply(mutation("event", sleepID, "upsert", 1, map[string]any{"ended_at": "2026-10-09T05:10:00Z"}))[0]
	if data(woke)["ended_at"] != "2026-10-09T05:10:00Z" {
		t.Errorf("sleep end = %v", data(woke)["ended_at"])
	}
	// An end before the start is refused and leaves the row as it was.
	bad := a.push(mutation("event", sleepID, "upsert", 2, map[string]any{"ended_at": "2026-10-08T20:00:00Z"}))[0]
	if bad["error_code"] != "validation_failed" {
		t.Errorf("bad end = %v", bad)
	}
	// Changing the type of an existing event is not allowed.
	bad = a.push(mutation("event", sleepID, "upsert", 2, map[string]any{"type": "bottle", "amount_ml": 100}))[0]
	if bad["error_code"] != "validation_failed" {
		t.Errorf("type change = %v", bad)
	}
	// Reopening the session (clearing the end) is allowed.
	reopened := a.mustApply(mutation("event", sleepID, "upsert", 2, map[string]any{"ended_at": nil}))[0]
	if data(reopened)["ended_at"] != nil {
		t.Errorf("ended_at not cleared: %v", data(reopened))
	}

	_, body := a.do("GET", "/babies/"+baby+"/events?type=sleep&type=diaper", nil)
	if n := len(body["events"].([]any)); n != 2 {
		t.Errorf("type filter returned %d events, want 2", n)
	}
}

func TestBabyLifecycle(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()

	res := a.mustApply(mutation("baby", baby, "upsert", 1, map[string]any{"notes": "allergic to nothing so far", "sex": "female"}))[0]
	if got := data(res); got["name"] != "Vera" || got["date_of_birth"] != "2026-07-01" || got["sex"] != "female" || got["version"] != float64(2) {
		t.Errorf("baby = %v", got)
	}
	_, body := a.do("GET", "/babies", nil)
	if n := len(body["babies"].([]any)); n != 1 {
		t.Errorf("%d babies listed", n)
	}

	a.mustApply(mutation("baby", baby, "delete", 2, nil))
	status, body := a.do("GET", "/babies/"+baby, nil)
	expectError(t, status, body, http.StatusNotFound, "not_found")
	rejected := a.push(mutation("event", uuid.NewString(), "upsert", 0, bottle(baby, "2026-10-08T09:00:00Z", 90)))[0]
	if rejected["error_code"] != "entity_deleted" {
		t.Errorf("event for deleted baby = %v", rejected)
	}
}

func TestConcurrentPushesProduceAGapFreeOrderedFeed(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	b := a.join("b@example.com")
	baby := a.createBaby()
	_, cursor := a.pullAll("", "500")

	const workers, perWorker = 8, 10
	var wg sync.WaitGroup
	for w := 0; w < workers; w++ {
		c := a
		if w%2 == 1 {
			c = b
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < perWorker; i++ {
				id := uuid.Must(uuid.NewV7()).String()
				status, body := c.do("POST", "/sync/push", map[string]any{"mutations": []any{
					mutation("event", id, "upsert", 0, bottle(baby, "2026-10-08T09:00:00Z", 90)),
				}})
				if status != http.StatusOK {
					t.Errorf("push: %d %v", status, body)
				}
			}
		}()
	}
	wg.Wait()

	changes, _ := a.pullAll(cursor, "7")
	if len(changes) != workers*perWorker {
		t.Fatalf("feed has %d changes, want %d", len(changes), workers*perWorker)
	}
	first := changes[0].Data["seq"].(float64)
	for i, c := range changes {
		if seq := c.Data["seq"].(float64); seq != first+float64(i) {
			t.Fatalf("seq at position %d = %v, want %v (gap or reordering)", i, seq, first+float64(i))
		}
	}
	var syncSeq int64
	if err := e.pool.QueryRow(context.Background(), "SELECT sync_seq FROM families WHERE id = $1", a.familyID).Scan(&syncSeq); err != nil {
		t.Fatal(err)
	}
	if last := changes[len(changes)-1].Data["seq"].(float64); float64(syncSeq) != last {
		t.Errorf("family counter = %d, last seq = %v", syncSeq, last)
	}
}

func TestCursorErrors(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	_, cursor := a.pullAll("", "500")

	status, body := a.do("GET", "/sync?cursor=garbage", nil)
	expectError(t, status, body, http.StatusBadRequest, "invalid_request")
	status, body = a.do("GET", "/sync?limit=0", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
	status, body = a.do("GET", "/sync?limit=501", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")

	// History was rewritten (database restored): old cursors are refused.
	if _, err := e.pool.Exec(context.Background(), "UPDATE families SET sync_epoch = gen_random_uuid() WHERE id = $1", a.familyID); err != nil {
		t.Fatal(err)
	}
	status, body = a.do("GET", "/sync?cursor="+cursor, nil)
	expectError(t, status, body, http.StatusGone, "cursor_expired")
	if changes, _ := a.pullAll("", "500"); len(changes) != 1 {
		t.Errorf("fresh pull after epoch change returned %d changes", len(changes))
	}
}

func TestFamilySettingsAppearInFeed(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	b := a.join("b@example.com")
	_, cursor := b.pullAll("", "500")

	status, body := a.do("PATCH", "/family", map[string]any{"ai_enabled": true, "timezone": "Atlantic/Canary"})
	if status != http.StatusOK {
		t.Fatalf("patch: %d %v", status, body)
	}
	changes, _ := b.pullAll(cursor, "500")
	if len(changes) != 1 || changes[0].Entity != "family" || changes[0].Data["ai_enabled"] != true ||
		changes[0].Data["timezone"] != "Atlantic/Canary" || changes[0].Data["name"] != "Family of a@example.com" {
		t.Errorf("feed = %v", changes)
	}

	status, body = a.do("PATCH", "/family", map[string]any{"timezone": "Nowhere/Land", "name": ""})
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
	// The family entity is read-only through sync.
	res := a.push(mutation("family", a.familyID, "upsert", 2, map[string]any{"ai_enabled": false}))[0]
	if res["status"] != "rejected" {
		t.Errorf("family mutation = %v", res)
	}
}

func TestListEventsPaging(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	var muts []map[string]any
	for h := 0; h < 5; h++ {
		muts = append(muts, mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0,
			bottle(baby, fmt.Sprintf("2026-10-08T%02d:00:00Z", 8+h), 100+h)))
	}
	// Two events at the same instant must not be skipped or repeated at a page boundary.
	muts = append(muts, mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, bottle(baby, "2026-10-08T10:00:00Z", 999)))
	a.mustApply(muts...)

	var amounts []float64
	path := "/babies/" + baby + "/events?limit=2"
	for pages := 0; pages < 10; pages++ {
		status, body := a.do("GET", path, nil)
		if status != http.StatusOK {
			t.Fatalf("list: %d %v", status, body)
		}
		for _, raw := range body["events"].([]any) {
			amounts = append(amounts, raw.(map[string]any)["amount_ml"].(float64))
		}
		token, _ := body["next_page_token"].(string)
		if token == "" {
			break
		}
		path = "/babies/" + baby + "/events?limit=2&page_token=" + token
	}
	if len(amounts) != 6 || amounts[0] != 104 || amounts[5] != 100 {
		t.Errorf("amounts newest-first = %v", amounts)
	}

	_, body := a.do("GET", "/babies/"+baby+"/events?from=2026-10-08T09:00:00Z&to=2026-10-08T11:00:00Z", nil)
	if n := len(body["events"].([]any)); n != 3 {
		t.Errorf("range filter returned %d, want 3 (to is exclusive)", n)
	}
	status, body := a.do("GET", "/babies/"+baby+"/events?from=yesterday&limit=9999&page_token=x", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
}

func TestPushRequestLimits(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	var many []map[string]any
	for i := 0; i < 201; i++ {
		many = append(many, mutation("event", uuid.NewString(), "delete", 0, nil))
	}
	status, body := a.do("POST", "/sync/push", map[string]any{"mutations": many})
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")

	status, body = a.do("POST", "/sync/push", map[string]any{"mutations": []any{
		map[string]any{"entity": "event", "entity_id": uuid.NewString(), "op": "delete"},
	}})
	expectError(t, status, body, http.StatusBadRequest, "invalid_request")

	if status, body := a.do("POST", "/sync/push", map[string]any{"mutations": []any{}}); status != http.StatusOK {
		t.Errorf("empty push: %d %v", status, body)
	}
}

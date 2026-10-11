package httpapi_test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"babytracker/internal/httpapi"
	"babytracker/internal/store"
)

func TestHousekeepingRemovesOnlyExpiredBookkeeping(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	ctx := context.Background()
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	a.createBottle(baby, 120)
	// A second phone signs in; an invitation is created and never used (it expires after two days).
	relogin := e.login("a@example.com", testPassword)
	a.do("POST", "/auth/invites", nil)
	count := func(table string) (n int) {
		if err := e.pool.QueryRow(ctx, "SELECT count(*) FROM "+table).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	tokens, mutations := count("refresh_tokens"), count("applied_mutations")

	// Nothing is old enough yet.
	if pruned, err := store.Prune(ctx, e.pool, time.Now()); err != nil || pruned != (store.Pruned{}) {
		t.Fatalf("pruned too early: %+v, %v", pruned, err)
	}

	// Five weeks on: the setup code used a month ago and the invitation that expired a month
	// ago are gone, and nothing else. Sessions are intact.
	pruned, err := store.Prune(ctx, e.pool, time.Now().Add(35*24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if pruned.Invites != 2 || pruned.RefreshTokens != 0 || pruned.Mutations != 0 || count("applied_mutations") != mutations {
		t.Errorf("after five weeks: %+v", pruned)
	}
	if status, body := relogin.do("GET", "/me", nil); status != http.StatusOK {
		t.Errorf("a live session was affected: %d %v", status, body)
	}

	// Much later every token has expired and the idempotency log is past its retention;
	// the family's data is untouched.
	pruned, err = store.Prune(ctx, e.pool, time.Now().Add(200*24*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if int(pruned.RefreshTokens) != tokens || int(pruned.Mutations) != mutations || count("refresh_tokens") != 0 {
		t.Errorf("much later: %+v, want %d tokens and %d mutations", pruned, tokens, mutations)
	}
	if count("events") != 1 || count("babies") != 1 || count("users") != 1 || count("devices") != 2 {
		t.Errorf("family data was pruned: events %d, babies %d", count("events"), count("babies"))
	}
}

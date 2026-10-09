package httpapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"babytracker/internal/ai"
	"babytracker/internal/auth"
	"babytracker/internal/httpapi"
	"babytracker/internal/store"
	"babytracker/internal/syncsvc"
	"babytracker/internal/testutil"
)

const (
	testPassword     = "a-long-enough-password"
	testAIDailyLimit = 3
)

type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

type env struct {
	t     *testing.T
	pool  *pgxpool.Pool
	auth  *auth.Service
	clock *fakeClock
	srv   *httptest.Server
	// model is the language model the server is configured with; tests script its answers.
	model *fakeModel
}

// fakeModel stands in for a language-model provider and records what it was sent.
type fakeModel struct {
	mu       sync.Mutex
	answers  []string // returned in order; the last one repeats
	fail     bool
	requests []ai.Request
}

func (m *fakeModel) Name() string  { return "fake" }
func (m *fakeModel) Model() string { return "fake-1" }

func (m *fakeModel) Analyze(_ context.Context, req ai.Request) (ai.Response, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.requests = append(m.requests, req)
	if m.fail {
		return ai.Response{}, ai.ErrProvider
	}
	answer := m.answers[min(len(m.requests), len(m.answers))-1]
	return ai.Response{JSON: answer, InputTokens: 100, OutputTokens: 20}, nil
}

func (m *fakeModel) calls() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.requests)
}

const goodAnswer = `{"observations":[{"text":"The records cover seven days.","refs":["range.days"]}],"interpretation":[{"text":"This may suggest a steady rhythm."}],"suggestions":[]}`

func newEnv(t *testing.T, limits httpapi.RateLimits) *env {
	t.Helper()
	return newEnvWithModel(t, limits, &fakeModel{answers: []string{goodAnswer}})
}

// newEnvWithModel starts a server with the given AI provider; nil means none is configured.
func newEnvWithModel(t *testing.T, limits httpapi.RateLimits, model *fakeModel) *env {
	t.Helper()
	pool := testutil.NewPool(t)
	clock := &fakeClock{now: time.Now().UTC().Truncate(time.Second)}
	issuer := auth.NewTokenIssuer([]byte(strings.Repeat("k", 32)), 15*time.Minute, clock.Now)
	authSvc, err := auth.NewService(pool, issuer, 90*24*time.Hour, clock.Now)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	var provider ai.Provider
	if model != nil {
		provider = model
	}
	srv := httptest.NewServer(httpapi.New(httpapi.Deps{
		Pool: pool, Auth: authSvc, Sync: syncsvc.NewService(pool, clock.Now), Issuer: issuer,
		AI:     ai.NewService(provider, store.New(pool), testAIDailyLimit, clock.Now, logger),
		Logger: logger, Now: clock.Now, RateLimits: limits,
	}))
	t.Cleanup(srv.Close)
	return &env{t: t, pool: pool, auth: authSvc, clock: clock, srv: srv, model: model}
}

// client is one signed-in device.
type client struct {
	e        *env
	email    string
	deviceID string
	access   string
	refresh  string
	userID   string
	familyID string
}

func device(id string) map[string]any {
	return map[string]any{"id": id, "name": "Test phone", "platform": "android", "app_version": "1.0.0"}
}

// newFamily provisions a family the way an operator would and signs the first parent in.
func (e *env) newFamily(email string) *client {
	e.t.Helper()
	_, _, code, err := e.auth.CreateFamily(context.Background(), auth.CreateFamilyInput{
		Name: "Family of " + email, Timezone: "Europe/Madrid", Email: email, DisplayName: "Parent",
	})
	if err != nil {
		e.t.Fatalf("create family: %v", err)
	}
	c := &client{e: e, email: email, deviceID: uuid.NewString()}
	status, body := c.do("POST", "/auth/invites/redeem", map[string]any{
		"code": code, "password": testPassword, "device": device(c.deviceID),
	})
	c.adopt(status, body)
	return c
}

// login signs the same user in on another device.
func (e *env) login(email, password string) *client {
	e.t.Helper()
	c := &client{e: e, email: email, deviceID: uuid.NewString()}
	status, body := c.do("POST", "/auth/login", map[string]any{
		"email": email, "password": password, "device": device(c.deviceID),
	})
	c.adopt(status, body)
	return c
}

// join creates a second parent in c's family through an invite code.
func (c *client) join(email string) *client {
	c.e.t.Helper()
	status, body := c.do("POST", "/auth/invites", nil)
	if status != http.StatusCreated {
		c.e.t.Fatalf("create invite: %d %v", status, body)
	}
	other := &client{e: c.e, email: email, deviceID: uuid.NewString()}
	status, body = other.do("POST", "/auth/invites/redeem", map[string]any{
		"code": body["code"], "email": email, "display_name": "Other parent",
		"password": testPassword, "device": device(other.deviceID),
	})
	other.adopt(status, body)
	return other
}

func (c *client) adopt(status int, body map[string]any) {
	c.e.t.Helper()
	if status != http.StatusOK {
		c.e.t.Fatalf("sign-in failed: %d %v", status, body)
	}
	c.access, c.refresh = body["access_token"].(string), body["refresh_token"].(string)
	c.userID = body["user"].(map[string]any)["id"].(string)
	c.familyID = body["family"].(map[string]any)["id"].(string)
}

func (c *client) do(method, path string, body any) (int, map[string]any) {
	c.e.t.Helper()
	var reader io.Reader
	if raw, ok := body.([]byte); ok {
		reader = bytes.NewReader(raw)
	} else if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			c.e.t.Fatal(err)
		}
		reader = bytes.NewReader(raw)
	}
	req, err := http.NewRequest(method, c.e.srv.URL+"/api/v1"+path, reader)
	if err != nil {
		c.e.t.Fatal(err)
	}
	if c.access != "" {
		req.Header.Set("Authorization", "Bearer "+c.access)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.e.t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	if len(bytes.TrimSpace(raw)) > 0 {
		if err := json.Unmarshal(raw, &out); err != nil {
			c.e.t.Fatalf("%s %s: non-JSON response (%d): %s", method, path, resp.StatusCode, raw)
		}
	}
	return resp.StatusCode, out
}

// expectError asserts an error response with the given status and code.
func expectError(t *testing.T, status int, body map[string]any, wantStatus int, wantCode string) {
	t.Helper()
	code := ""
	if e, ok := body["error"].(map[string]any); ok {
		code, _ = e["code"].(string)
	}
	if status != wantStatus || code != wantCode {
		t.Errorf("got %d %q, want %d %q (body %v)", status, code, wantStatus, wantCode, body)
	}
}

func mutation(entity, id, op string, baseVersion int, fields map[string]any) map[string]any {
	m := map[string]any{
		"mutation_id": uuid.Must(uuid.NewV7()).String(), "entity": entity, "entity_id": id,
		"op": op, "base_version": baseVersion,
	}
	if fields != nil {
		m["fields"] = fields
	}
	return m
}

// push sends mutations and returns the per-mutation results.
func (c *client) push(mutations ...map[string]any) []map[string]any {
	c.e.t.Helper()
	status, body := c.do("POST", "/sync/push", map[string]any{"mutations": mutations})
	if status != http.StatusOK {
		c.e.t.Fatalf("push: %d %v", status, body)
	}
	raw := body["results"].([]any)
	if len(raw) != len(mutations) {
		c.e.t.Fatalf("push returned %d results for %d mutations", len(raw), len(mutations))
	}
	out := make([]map[string]any, len(raw))
	for i, r := range raw {
		out[i] = r.(map[string]any)
	}
	return out
}

// mustApply pushes and fails the test unless every mutation was applied.
func (c *client) mustApply(mutations ...map[string]any) []map[string]any {
	c.e.t.Helper()
	results := c.push(mutations...)
	for i, r := range results {
		if r["status"] != "applied" {
			c.e.t.Fatalf("mutation %d not applied: %v", i, r)
		}
	}
	return results
}

func (c *client) createBaby() string {
	c.e.t.Helper()
	id := uuid.Must(uuid.NewV7()).String()
	c.mustApply(mutation("baby", id, "upsert", 0, map[string]any{"name": "Vera", "date_of_birth": "2026-07-01"}))
	return id
}

func bottle(babyID string, startedAt string, ml int) map[string]any {
	return map[string]any{
		"baby_id": babyID, "type": "bottle", "started_at": startedAt, "timezone": "Europe/Madrid", "amount_ml": ml,
	}
}

func (c *client) createBottle(babyID string, ml int) string {
	c.e.t.Helper()
	id := uuid.Must(uuid.NewV7()).String()
	c.mustApply(mutation("event", id, "upsert", 0, bottle(babyID, "2026-10-08T14:32:00Z", ml)))
	return id
}

type change struct {
	Entity string
	Data   map[string]any
}

// pullAll follows the feed from cursor to its end with the given page size.
func (c *client) pullAll(cursor string, limit string) (changes []change, next string) {
	c.e.t.Helper()
	for {
		path := "/sync?limit=" + limit
		if cursor != "" {
			path += "&cursor=" + cursor
		}
		status, body := c.do("GET", path, nil)
		if status != http.StatusOK {
			c.e.t.Fatalf("pull: %d %v", status, body)
		}
		for _, raw := range body["changes"].([]any) {
			m := raw.(map[string]any)
			changes = append(changes, change{Entity: m["entity"].(string), Data: m["data"].(map[string]any)})
		}
		cursor = body["next_cursor"].(string)
		if body["has_more"] != true {
			return changes, cursor
		}
	}
}

func data(result map[string]any) map[string]any {
	d, _ := result["data"].(map[string]any)
	return d
}

func authInput(email string) auth.CreateFamilyInput {
	return auth.CreateFamilyInput{Name: "Pending", Timezone: "Europe/Madrid", Email: email, DisplayName: "Parent"}
}

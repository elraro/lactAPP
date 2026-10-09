package httpapi_test

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"babytracker/internal/httpapi"
)

// recordSomething gives the baby a few events so that there is something to explain.
func recordSomething(a *client, baby string, now time.Time) {
	var muts []map[string]any
	for i := 1; i <= 3; i++ {
		at := now.Add(-time.Duration(i) * 3 * time.Hour).Format(time.RFC3339)
		muts = append(muts, mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, bottle(baby, at, 120)))
	}
	note := bottle(baby, now.Add(-time.Hour).Format(time.RFC3339), 90)
	note["notes"] = "PRIVATE-NOTE-ABOUT-GRANDMA"
	muts = append(muts, mutation("event", uuid.Must(uuid.NewV7()).String(), "upsert", 0, note))
	a.mustApply(muts...)
}

func analysisRequest(baby string) map[string]any {
	return map[string]any{"baby_id": baby, "kind": "overview", "days": 7, "locale": "es"}
}

func TestAIRequiresOperatorAndFamilyConsent(t *testing.T) {
	// No provider configured on the server: the feature does not exist.
	bare := newEnvWithModel(t, httpapi.RateLimits{}, nil)
	a := bare.newFamily("a@example.com")
	baby := a.createBaby()
	status, body := a.do("GET", "/ai/status", nil)
	if status != http.StatusOK || body["configured"] != false || body["provider"] != nil {
		t.Errorf("status without provider: %d %v", status, body)
	}
	a.do("PATCH", "/family", map[string]any{"ai_enabled": true})
	status, body = a.do("POST", "/ai/analyses", analysisRequest(baby))
	expectError(t, status, body, http.StatusForbidden, "ai_not_configured")

	// Provider configured, family has not switched AI on: nothing is sent.
	e := newEnv(t, httpapi.RateLimits{})
	b := e.newFamily("b@example.com")
	baby = b.createBaby()
	recordSomething(b, baby, e.clock.Now())
	status, body = b.do("GET", "/ai/status", nil)
	if body["configured"] != true || body["enabled"] != false || body["provider"] != "fake" || body["remaining_today"] != float64(testAIDailyLimit) {
		t.Errorf("status: %d %v", status, body)
	}
	status, body = b.do("POST", "/ai/analyses", analysisRequest(baby))
	expectError(t, status, body, http.StatusForbidden, "ai_disabled")
	if e.model.calls() != 0 {
		t.Fatalf("the provider was called %d times without consent", e.model.calls())
	}

	// Switching it off again takes effect immediately.
	b.do("PATCH", "/family", map[string]any{"ai_enabled": true})
	if status, body := b.do("POST", "/ai/analyses", analysisRequest(baby)); status != http.StatusOK {
		t.Fatalf("analysis with consent: %d %v", status, body)
	}
	b.do("PATCH", "/family", map[string]any{"ai_enabled": false})
	status, body = b.do("POST", "/ai/analyses", map[string]any{"baby_id": baby, "kind": "feeding", "days": 7, "locale": "es"})
	expectError(t, status, body, http.StatusForbidden, "ai_disabled")
	if e.model.calls() != 1 {
		t.Errorf("provider calls = %d, want 1", e.model.calls())
	}
}

func TestAIAnalysis(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	recordSomething(a, baby, e.clock.Now())
	a.do("PATCH", "/family", map[string]any{"ai_enabled": true})

	status, body := a.do("POST", "/ai/analyses", analysisRequest(baby))
	if status != http.StatusOK {
		t.Fatalf("analysis: %d %v", status, body)
	}
	result := body["result"].(map[string]any)
	if len(result["observations"].([]any)) != 1 || len(result["interpretation"].([]any)) != 1 || result["suggestions"] == nil {
		t.Errorf("result = %v", result)
	}
	if body["cached"] != false || body["provider"] != "fake" || body["model"] != "fake-1" || body["kind"] != "overview" || body["locale"] != "es" {
		t.Errorf("analysis = %v", body)
	}

	// What was sent: instructions in the requested language and a context of summaries.
	sent := e.model.requests[0]
	if !strings.Contains(sent.System, "Spanish") || !strings.Contains(sent.User, `"analysis_kind":"overview"`) || !strings.Contains(sent.User, `"total_ml":450`) {
		t.Errorf("request: system has Spanish = %v; user = %.300s", strings.Contains(sent.System, "Spanish"), sent.User)
	}
	// Never the baby's name, a note, an e-mail or an identifier.
	for _, private := range []string{"Vera", "PRIVATE-NOTE", "a@example.com", baby, a.familyID, a.userID, "2026-07-01"} {
		if strings.Contains(sent.User, private) || strings.Contains(sent.System, private) {
			t.Errorf("the request to the provider contains %q", private)
		}
	}

	// The same question again is answered from the database, without a provider call.
	_, again := a.do("POST", "/ai/analyses", analysisRequest(baby))
	if again["cached"] != true || again["id"] != body["id"] || e.model.calls() != 1 {
		t.Errorf("repeat: cached %v, same id %v, provider calls %d", again["cached"], again["id"] == body["id"], e.model.calls())
	}
	// A different language is a different question.
	en := analysisRequest(baby)
	en["locale"] = "en"
	if _, other := a.do("POST", "/ai/analyses", en); other["cached"] != false || e.model.calls() != 2 {
		t.Errorf("other locale: %v, calls %d", other["cached"], e.model.calls())
	}

	_, list := a.do("GET", "/ai/analyses?baby_id="+baby, nil)
	if n := len(list["analyses"].([]any)); n != 2 {
		t.Errorf("listed %d analyses, want 2", n)
	}
	_, st := a.do("GET", "/ai/status", nil)
	if st["remaining_today"] != float64(testAIDailyLimit-2) {
		t.Errorf("remaining = %v", st["remaining_today"])
	}

	// The daily limit counts provider calls, not cached answers, and frees up a day later.
	sleep := analysisRequest(baby)
	sleep["kind"] = "sleep"
	if status, body := a.do("POST", "/ai/analyses", sleep); status != http.StatusOK {
		t.Fatalf("third analysis: %d %v", status, body)
	}
	feeding := analysisRequest(baby)
	feeding["kind"] = "feeding"
	status, body = a.do("POST", "/ai/analyses", feeding)
	expectError(t, status, body, http.StatusTooManyRequests, "rate_limited")
	if status, _ := a.do("POST", "/ai/analyses", analysisRequest(baby)); status != http.StatusOK {
		t.Error("a cached answer must still be served at the limit")
	}
	e.clock.Advance(25 * time.Hour)
	// The access token expired in the meantime, as it would for a real app.
	_, session := (&client{e: e}).do("POST", "/auth/refresh", map[string]any{"refresh_token": a.refresh, "device_id": a.deviceID})
	a.access = session["access_token"].(string)
	if status, body := a.do("POST", "/ai/analyses", feeding); status != http.StatusOK {
		t.Errorf("after a day: %d %v", status, body)
	}
}

func TestAIAnswerIsValidatedBeforeItIsShown(t *testing.T) {
	invented := `{"observations":[{"text":"REM sleep is low.","refs":["calculated.sleep.rem_minutes"]}],"interpretation":[],"suggestions":[]}`

	// A bad first answer is retried once; the good second answer is used.
	e := newEnvWithModel(t, httpapi.RateLimits{}, &fakeModel{answers: []string{"I think the baby is fine!", goodAnswer}})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	recordSomething(a, baby, e.clock.Now())
	a.do("PATCH", "/family", map[string]any{"ai_enabled": true})
	if status, body := a.do("POST", "/ai/analyses", analysisRequest(baby)); status != http.StatusOK || e.model.calls() != 2 {
		t.Errorf("retry: %d %v after %d calls", status, body, e.model.calls())
	}

	// An answer that cites something that is not in the context is never stored or returned.
	e = newEnvWithModel(t, httpapi.RateLimits{}, &fakeModel{answers: []string{invented}})
	a = e.newFamily("a@example.com")
	baby = a.createBaby()
	recordSomething(a, baby, e.clock.Now())
	a.do("PATCH", "/family", map[string]any{"ai_enabled": true})
	status, body := a.do("POST", "/ai/analyses", analysisRequest(baby))
	expectError(t, status, body, http.StatusBadGateway, "ai_provider_error")
	if e.model.calls() != 2 {
		t.Errorf("provider calls = %d, want 2 (one retry)", e.model.calls())
	}
	if _, list := a.do("GET", "/ai/analyses?baby_id="+baby, nil); len(list["analyses"].([]any)) != 0 {
		t.Error("a rejected answer was stored")
	}

	// A provider failure is reported as such, and does not use up the daily allowance.
	e.model.fail = true
	status, body = a.do("POST", "/ai/analyses", analysisRequest(baby))
	expectError(t, status, body, http.StatusBadGateway, "ai_provider_error")
	if _, st := a.do("GET", "/ai/status", nil); st["remaining_today"] != float64(testAIDailyLimit) {
		t.Errorf("remaining after failures = %v", st["remaining_today"])
	}
}

func TestAIValidationAndIsolation(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	owner := e.newFamily("owner@example.com")
	baby := owner.createBaby()
	recordSomething(owner, baby, e.clock.Now())
	owner.do("PATCH", "/family", map[string]any{"ai_enabled": true})
	owner.do("POST", "/ai/analyses", analysisRequest(baby))

	status, body := owner.do("POST", "/ai/analyses", map[string]any{"baby_id": baby, "kind": "horoscope", "days": 5, "locale": "fr"})
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
	if fields := body["error"].(map[string]any)["fields"].(map[string]any); len(fields) != 3 {
		t.Errorf("fields = %v", fields)
	}
	status, body = owner.do("GET", "/ai/analyses?baby_id=nope", nil)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")

	intruder := e.newFamily("intruder@example.com")
	intruder.do("PATCH", "/family", map[string]any{"ai_enabled": true})
	calls := e.model.calls()
	status, body = intruder.do("POST", "/ai/analyses", analysisRequest(baby))
	expectError(t, status, body, http.StatusNotFound, "not_found")
	status, body = intruder.do("GET", "/ai/analyses?baby_id="+baby, nil)
	expectError(t, status, body, http.StatusNotFound, "not_found")
	if e.model.calls() != calls {
		t.Error("another family's data reached the provider")
	}
	// One family's use does not count against another's allowance.
	if _, st := intruder.do("GET", "/ai/status", nil); st["remaining_today"] != float64(testAIDailyLimit) {
		t.Errorf("intruder's remaining = %v", st["remaining_today"])
	}
}

package ai

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"babytracker/internal/predict"
	"babytracker/internal/stats"
)

func madrid(t *testing.T) *time.Location {
	t.Helper()
	loc, err := time.LoadLocation("Europe/Madrid")
	if err != nil {
		t.Fatal(err)
	}
	return loc
}

func sampleStats(t *testing.T) stats.Output {
	t.Helper()
	ml, kind := 120, "wet"
	nap := "nap"
	end := time.Date(2026, 10, 8, 9, 0, 0, 0, time.UTC)
	out, err := stats.Compute(stats.Input{
		Timezone: "Europe/Madrid", End: "2026-10-08", Days: 7, Now: time.Date(2026, 10, 8, 12, 0, 0, 0, time.UTC),
		Events: []stats.Event{
			{ID: "a", Type: "bottle", StartedAt: time.Date(2026, 10, 8, 7, 0, 0, 0, time.UTC), Timezone: "Europe/Madrid", AmountMl: &ml},
			{ID: "b", Type: "diaper", StartedAt: time.Date(2026, 10, 8, 7, 30, 0, 0, time.UTC), Timezone: "Europe/Madrid", DiaperKind: &kind},
			{ID: "c", Type: "sleep", StartedAt: time.Date(2026, 10, 8, 8, 0, 0, 0, time.UTC), EndedAt: &end, Timezone: "Europe/Madrid", SleepKind: &nap},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestContextContainsOnlySummaries(t *testing.T) {
	now := time.Date(2026, 10, 8, 12, 0, 0, 0, time.UTC)
	birth := time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC)
	s := sampleStats(t)

	overview := BuildContext(KindOverview, birth, now, madrid(t), s, predict.Output{Status: predict.StatusInsufficient})
	raw, _ := json.Marshal(overview)
	var doc map[string]any
	_ = json.Unmarshal(raw, &doc)
	// 1 July to 8 October is 99 days: the age is sent, the date of birth is not.
	if overview.Subject.AgeDays != 99 || strings.Contains(string(raw), "2026-07-01") {
		t.Errorf("age = %d; context = %s", overview.Subject.AgeDays, raw)
	}
	// No identifiers, names, notes or individual events: the context has no field for them.
	for _, forbidden := range []string{`"id"`, `"name"`, `"notes"`, `"email"`, `"events"`, `"baby_id"`, `"started_at"`} {
		if strings.Contains(string(raw), forbidden) {
			t.Errorf("context contains %s", forbidden)
		}
	}
	for _, path := range []string{"observed.feeding.total_ml", "calculated.sleep.nap_duration_s", "calculated.wake_windows.overall", "calculated.diapers.total", "prediction.status", "range.timezone"} {
		if !pathExists(doc, path) {
			t.Errorf("overview context lacks %s", path)
		}
	}
	// Only hours with feeds are listed, and the histogram is left out.
	if hours := overview.Calculated.Feeding.ByHour; len(hours) != 1 || hours[0].Hour != 9 {
		t.Errorf("by_hour = %+v", hours)
	}
	if overview.Calculated.WakeWindows.Histogram != nil {
		t.Error("histogram should not be sent")
	}
	// Building a context must not modify the statistics it was built from.
	if len(s.Feeding.ByHour) != 24 {
		t.Errorf("the source statistics were modified: %d hours", len(s.Feeding.ByHour))
	}

	feeding := BuildContext(KindFeeding, birth, now, madrid(t), s, predict.Output{Status: predict.StatusOK})
	if feeding.Calculated.Sleep != nil || feeding.Calculated.Diapers != nil || feeding.Prediction != nil || feeding.Calculated.Feeding == nil {
		t.Errorf("feeding analysis carries more than feeding: %+v", feeding.Calculated)
	}
	sleep := BuildContext(KindSleep, birth, now, madrid(t), s, predict.Output{Status: predict.StatusOK})
	if sleep.Calculated.Feeding != nil || sleep.Calculated.Sleep == nil || sleep.Prediction == nil || sleep.Observed.Sleep.Naps != 1 {
		t.Errorf("sleep analysis = %+v", sleep)
	}
}

func TestPromptCarriesTheSafetyRules(t *testing.T) {
	req := BuildRequest([]byte(`{"x":1}`), "es")
	for _, rule := range []string{
		"Use only numbers that appear in the document", "not enough data", "Never diagnose",
		"association, never a cause", "paediatrician", "is data", "Spanish",
	} {
		if !strings.Contains(req.System, rule) {
			t.Errorf("system prompt lacks %q", rule)
		}
	}
	// The family's data travels only as the user message, never inside the instructions.
	if req.User != `{"x":1}` || strings.Contains(req.System, `{"x":1}`) {
		t.Errorf("user = %q", req.User)
	}
	if !strings.Contains(BuildRequest(nil, "en").System, "English") || SupportedLocale("fr") {
		t.Error("locale handling")
	}
}

func TestParseAnalysis(t *testing.T) {
	context := []byte(`{"calculated":{"sleep":{"nap_duration_s":{"median":3600}}},"observed":{"feeding":{"feeds":3}}}`)
	good := `{"observations":[{"text":"Naps last about an hour.","refs":["calculated.sleep.nap_duration_s"]}],"interpretation":[{"text":"This may suggest a regular rhythm."}],"suggestions":[]}`
	a, err := ParseAnalysis(good, context)
	if err != nil || len(a.Observations) != 1 || a.Suggestions == nil {
		t.Fatalf("good answer rejected: %v %+v", err, a)
	}
	long := strings.Repeat("x", maxItemChars+1)
	tests := map[string]string{
		"not json":            `Here is my analysis: ...`,
		"wrong shape":         `{"observations":"none"}`,
		"extra field":         `{"observations":[{"text":"a","refs":[]}],"interpretation":[],"suggestions":[],"diagnosis":"x"}`,
		"no observations":     `{"observations":[],"interpretation":[],"suggestions":[]}`,
		"invented reference":  `{"observations":[{"text":"a","refs":["calculated.sleep.rem_cycles"]}],"interpretation":[],"suggestions":[]}`,
		"reference into leaf": `{"observations":[{"text":"a","refs":["observed.feeding.feeds.per_day"]}],"interpretation":[],"suggestions":[]}`,
		"empty text":          `{"observations":[{"text":"  ","refs":[]}],"interpretation":[],"suggestions":[]}`,
		"oversized text":      `{"observations":[{"text":"` + long + `","refs":[]}],"interpretation":[],"suggestions":[]}`,
		"too many items":      `{"observations":[{"text":"a","refs":[]}],"interpretation":[],"suggestions":[{"text":"1"},{"text":"2"},{"text":"3"},{"text":"4"},{"text":"5"},{"text":"6"},{"text":"7"}]}`,
	}
	for name, raw := range tests {
		if _, err := ParseAnalysis(raw, context); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestSchemaIsStrict(t *testing.T) {
	var walk func(node any)
	walk = func(node any) {
		object, ok := node.(map[string]any)
		if !ok {
			return
		}
		if object["type"] == "object" {
			if object["additionalProperties"] != false || object["required"] == nil {
				t.Errorf("object schema is not strict: %v", object)
			}
			for _, property := range object["properties"].(map[string]any) {
				walk(property)
			}
		}
		walk(object["items"])
	}
	walk(Schema())
}

func TestFactory(t *testing.T) {
	if p, err := NewProvider(Settings{}); p != nil || err != nil {
		t.Errorf("no provider configured: %v, %v", p, err)
	}
	p, err := NewProvider(Settings{Provider: "anthropic", APIKey: "k"})
	if err != nil || p.Name() != "anthropic" || p.Model() != "claude-opus-5-5" {
		t.Errorf("anthropic default = %v, %v", p, err)
	}
	p, err = NewProvider(Settings{Provider: "ollama", BaseURL: "http://localhost:11434/v1", Model: "llama"})
	if err != nil || p.Name() != "ollama" {
		t.Errorf("ollama = %v, %v", p, err)
	}
	for _, bad := range []Settings{
		{Provider: "anthropic"}, {Provider: "openai", APIKey: "k"}, {Provider: "ollama", Model: "m"}, {Provider: "skynet"},
	} {
		if _, err := NewProvider(bad); err == nil {
			t.Errorf("%+v accepted", bad)
		}
	}
}

// fakeUpstream records the request and answers with the given status and body.
func fakeUpstream(t *testing.T, status int, body string) (*httptest.Server, *http.Request, *map[string]any) {
	t.Helper()
	var got http.Request
	sent := map[string]any{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = *r
		raw, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(raw, &sent)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}))
	t.Cleanup(srv.Close)
	return srv, &got, &sent
}

const answerJSON = `{"observations":[{"text":"ok","refs":[]}],"interpretation":[],"suggestions":[]}`

func anthropicMessage(stopReason, text string) string {
	msg := map[string]any{
		"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
		"content":     []any{map[string]any{"type": "text", "text": text}},
		"stop_reason": stopReason, "stop_sequence": nil,
		"usage": map[string]any{"input_tokens": 1200, "output_tokens": 150},
	}
	if stopReason == "refusal" {
		msg["stop_details"] = map[string]any{"type": "refusal", "category": "bio", "explanation": "x"}
	}
	raw, _ := json.Marshal(msg)
	return string(raw)
}

func TestAnthropicProvider(t *testing.T) {
	srv, got, sent := fakeUpstream(t, 200, anthropicMessage("end_turn", answerJSON))
	p := NewAnthropic("test-key", "", srv.URL)
	resp, err := p.Analyze(context.Background(), BuildRequest([]byte(`{"observed":{}}`), "en"))
	if err != nil {
		t.Fatal(err)
	}
	if resp.JSON != answerJSON || resp.InputTokens != 1200 || resp.OutputTokens != 150 {
		t.Errorf("response = %+v", resp)
	}
	if got.URL.Path != "/v1/messages" || got.Header.Get("X-Api-Key") != "test-key" {
		t.Errorf("request to %s with key %q", got.URL.Path, got.Header.Get("X-Api-Key"))
	}
	body := *sent
	if body["model"] != "claude-opus-5-5" || body["max_tokens"] != float64(16000) {
		t.Errorf("model %v, max_tokens %v", body["model"], body["max_tokens"])
	}
	format := body["output_config"].(map[string]any)["format"].(map[string]any)
	if format["type"] != "json_schema" || format["schema"].(map[string]any)["type"] != "object" {
		t.Errorf("output_config.format = %v", format)
	}
	// A declined request is re-served by another model inside the same call.
	if body["fallbacks"] != "default" || !strings.Contains(got.Header.Get("Anthropic-Beta"), "server-side-fallback-2026-07-01") {
		t.Errorf("fallbacks = %v, beta header %q", body["fallbacks"], got.Header.Get("Anthropic-Beta"))
	}
	// Thinking, sampling parameters and assistant prefill are not sent: defaults apply.
	for _, absent := range []string{"thinking", "temperature", "top_p"} {
		if _, ok := body[absent]; ok {
			t.Errorf("%s should not be sent", absent)
		}
	}
	messages := body["messages"].([]any)
	if len(messages) != 1 || messages[0].(map[string]any)["role"] != "user" {
		t.Errorf("messages = %v", messages)
	}

	// A model without server-side fallback is called without the field.
	srv, got, sent = fakeUpstream(t, 200, anthropicMessage("end_turn", answerJSON))
	if _, err := NewAnthropic("k", "claude-haiku-5-5", srv.URL).Analyze(context.Background(), BuildRequest(nil, "en")); err != nil {
		t.Fatal(err)
	}
	if _, ok := (*sent)["fallbacks"]; ok || got.Header.Get("Anthropic-Beta") != "" {
		t.Errorf("fallbacks sent for a model that does not support them")
	}
}

func TestAnthropicProviderFailures(t *testing.T) {
	tests := []struct {
		name   string
		status int
		body   string
		want   string
	}{
		{"refusal", 200, anthropicMessage("refusal", ""), "declined (bio)"},
		{"truncated", 200, anthropicMessage("max_tokens", `{"observations":[`), "max_tokens"},
		{"bad request", 400, `{"type":"error","error":{"type":"invalid_request_error","message":"secret details"}}`, "HTTP 400 (invalid_request_error)"},
		{"bad key", 401, `{"type":"error","error":{"type":"authentication_error","message":"x"}}`, "HTTP 401"},
	}
	for _, tc := range tests {
		srv, _, _ := fakeUpstream(t, tc.status, tc.body)
		_, err := NewAnthropic("k", "", srv.URL).Analyze(context.Background(), BuildRequest(nil, "en"))
		if !errors.Is(err, ErrProvider) || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: error = %v, want ErrProvider containing %q", tc.name, err, tc.want)
		}
		// The upstream message may echo the request; it must not travel in our error.
		if err != nil && strings.Contains(err.Error(), "secret details") {
			t.Errorf("%s: upstream message leaked into the error", tc.name)
		}
	}
}

func TestOpenAICompatibleProvider(t *testing.T) {
	ok := `{"choices":[{"message":{"role":"assistant","content":` + string(mustJSON(answerJSON)) + `},"finish_reason":"stop"}],"usage":{"prompt_tokens":900,"completion_tokens":80}}`
	srv, got, sent := fakeUpstream(t, 200, ok)
	p := NewOpenAICompatible("ollama", srv.URL+"/v1/", "", "llama3")
	resp, err := p.Analyze(context.Background(), BuildRequest([]byte(`{}`), "es"))
	if err != nil || resp.JSON != answerJSON || resp.InputTokens != 900 {
		t.Fatalf("response = %+v, %v", resp, err)
	}
	if got.URL.Path != "/v1/chat/completions" || got.Header.Get("Authorization") != "" {
		t.Errorf("request to %s, authorization %q", got.URL.Path, got.Header.Get("Authorization"))
	}
	body := *sent
	messages := body["messages"].([]any)
	if body["model"] != "llama3" || len(messages) != 2 || messages[0].(map[string]any)["role"] != "system" {
		t.Errorf("body = %v", body)
	}
	if format := body["response_format"].(map[string]any); format["type"] != "json_schema" {
		t.Errorf("response_format = %v", format)
	}

	srv, got, _ = fakeUpstream(t, 200, ok)
	if _, err := NewOpenAICompatible("openai", srv.URL, "sk-test", "m").Analyze(context.Background(), BuildRequest(nil, "en")); err != nil {
		t.Fatal(err)
	}
	if got.Header.Get("Authorization") != "Bearer sk-test" {
		t.Errorf("authorization = %q", got.Header.Get("Authorization"))
	}

	for name, upstream := range map[string][2]any{
		"server error": {500, `oops`},
		"not json":     {200, `<html>`},
		"no choices":   {200, `{"choices":[]}`},
		"truncated":    {200, `{"choices":[{"message":{"content":"{"},"finish_reason":"length"}]}`},
		"refusal":      {200, `{"choices":[{"message":{"content":"","refusal":"no"},"finish_reason":"stop"}]}`},
	} {
		srv, _, _ := fakeUpstream(t, upstream[0].(int), upstream[1].(string))
		if _, err := NewOpenAICompatible("openai", srv.URL, "k", "m").Analyze(context.Background(), BuildRequest(nil, "en")); !errors.Is(err, ErrProvider) {
			t.Errorf("%s: error = %v", name, err)
		}
	}
}

func mustJSON(s string) []byte {
	raw, err := json.Marshal(s)
	if err != nil {
		panic(err)
	}
	return raw
}

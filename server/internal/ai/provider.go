// Package ai turns deterministic statistics into a short natural-language explanation
// (docs/AI.md). The model never computes a number: it receives the output of the
// statistics and prediction engines and returns prose in a fixed JSON shape.
package ai

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

// Provider is one way of reaching a language model. Implementations must not log the
// request or the response.
type Provider interface {
	// Name identifies the provider to the user ("anthropic", "openai", "ollama").
	Name() string
	Model() string
	// Analyze sends the request and returns the model's raw JSON answer.
	Analyze(ctx context.Context, req Request) (Response, error)
}

type Request struct {
	// System holds the fixed instructions (prompt.go).
	System string
	// User holds the structured context as JSON text, and nothing else.
	User string
	// Schema is the JSON schema the answer must follow.
	Schema map[string]any
}

type Response struct {
	// JSON is the model's answer, expected to match Request.Schema.
	JSON         string
	InputTokens  int64
	OutputTokens int64
}

// ErrProvider wraps every failure of the upstream model: network, HTTP status, a refusal,
// or an answer that is not usable. The cause is for logs only.
var ErrProvider = errors.New("ai provider error")

func providerError(format string, args ...any) error {
	return fmt.Errorf("%w: %s", ErrProvider, fmt.Sprintf(format, args...))
}

// Analysis is the answer shown to the user, in three clearly separated parts
// (docs/AI.md §6).
type Analysis struct {
	// Observations restate recorded data and calculated statistics; Refs name the
	// context paths each one is based on.
	Observations []Observation `json:"observations"`
	// Interpretation is the model's reading of them, labelled as AI-generated in the app.
	Interpretation []Item `json:"interpretation"`
	Suggestions    []Item `json:"suggestions"`
}

type Observation struct {
	Text string   `json:"text"`
	Refs []string `json:"refs"`
}

type Item struct {
	Text string `json:"text"`
}

const (
	maxItemsPerSection = 6
	maxItemChars       = 600
)

// Schema is the JSON schema of Analysis, in the subset accepted by structured outputs.
func Schema() map[string]any {
	text := map[string]any{"type": "string"}
	item := map[string]any{
		"type": "object", "additionalProperties": false, "required": []string{"text"},
		"properties": map[string]any{"text": text},
	}
	observation := map[string]any{
		"type": "object", "additionalProperties": false, "required": []string{"text", "refs"},
		"properties": map[string]any{
			"text": text,
			"refs": map[string]any{"type": "array", "items": map[string]any{"type": "string"}},
		},
	}
	list := func(of map[string]any) map[string]any { return map[string]any{"type": "array", "items": of} }
	return map[string]any{
		"type": "object", "additionalProperties": false,
		"required": []string{"observations", "interpretation", "suggestions"},
		"properties": map[string]any{
			"observations":   list(observation),
			"interpretation": list(item),
			"suggestions":    list(item),
		},
	}
}

// ParseAnalysis checks a model answer before it is stored or shown: valid JSON of the
// right shape, bounded in size, and every reference pointing at something that really is
// in the context it was given.
func ParseAnalysis(raw string, contextJSON []byte) (Analysis, error) {
	var a Analysis
	dec := json.NewDecoder(strings.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&a); err != nil {
		return Analysis{}, fmt.Errorf("not the expected JSON: %w", err)
	}
	if len(a.Observations) == 0 {
		return Analysis{}, errors.New("no observations")
	}
	var root any
	if err := json.Unmarshal(contextJSON, &root); err != nil {
		return Analysis{}, err
	}
	check := func(section string, n int, texts []string) error {
		if n > maxItemsPerSection {
			return fmt.Errorf("%s: too many items", section)
		}
		for _, t := range texts {
			if strings.TrimSpace(t) == "" || utf8.RuneCountInString(t) > maxItemChars {
				return fmt.Errorf("%s: empty or oversized text", section)
			}
		}
		return nil
	}
	var texts []string
	for _, o := range a.Observations {
		texts = append(texts, o.Text)
		for _, ref := range o.Refs {
			if !pathExists(root, ref) {
				return Analysis{}, fmt.Errorf("observation refers to %q, which is not in the context", ref)
			}
		}
	}
	if err := check("observations", len(a.Observations), texts); err != nil {
		return Analysis{}, err
	}
	for name, items := range map[string][]Item{"interpretation": a.Interpretation, "suggestions": a.Suggestions} {
		texts = texts[:0]
		for _, it := range items {
			texts = append(texts, it.Text)
		}
		if err := check(name, len(items), texts); err != nil {
			return Analysis{}, err
		}
	}
	// Never nil, so that the JSON sent to the app always has three arrays.
	if a.Interpretation == nil {
		a.Interpretation = []Item{}
	}
	if a.Suggestions == nil {
		a.Suggestions = []Item{}
	}
	for i := range a.Observations {
		if a.Observations[i].Refs == nil {
			a.Observations[i].Refs = []string{}
		}
	}
	return a, nil
}

// pathExists reports whether a dotted path such as "calculated.sleep.nap_duration_s"
// names a value in the decoded context. Arrays are not indexed: a path may end at one.
func pathExists(root any, path string) bool {
	if path == "" {
		return false
	}
	current := root
	for _, key := range strings.Split(path, ".") {
		object, ok := current.(map[string]any)
		if !ok {
			return false
		}
		if current, ok = object[key]; !ok {
			return false
		}
	}
	return true
}

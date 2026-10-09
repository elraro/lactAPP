package ai

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"babytracker/internal/apperr"
	"babytracker/internal/store"
)

const (
	limitWindow = 24 * time.Hour
	// One retry when the answer does not pass ParseAnalysis (docs/AI.md §6).
	attempts = 2
)

var (
	ErrNotConfigured = apperr.New(http.StatusForbidden, "ai_not_configured")
	ErrDisabled      = apperr.New(http.StatusForbidden, "ai_disabled")
	errRateLimited   = apperr.New(http.StatusTooManyRequests, "rate_limited")
	errUpstream      = apperr.New(http.StatusBadGateway, "ai_provider_error")
)

// Service applies the rules around a Provider: consent, a daily limit per family,
// reuse of identical answers, and validation of what comes back.
type Service struct {
	// provider is nil when the operator configured none.
	provider   Provider
	q          *store.Queries
	dailyLimit int
	now        func() time.Time
	log        *slog.Logger
}

func NewService(provider Provider, q *store.Queries, dailyLimit int, now func() time.Time, log *slog.Logger) *Service {
	return &Service{provider: provider, q: q, dailyLimit: dailyLimit, now: now, log: log}
}

type Status struct {
	// Configured: the operator set up a provider. Enabled: the family switched AI on.
	Configured     bool   `json:"configured"`
	Enabled        bool   `json:"enabled"`
	Provider       string `json:"provider,omitempty"`
	Model          string `json:"model,omitempty"`
	RemainingToday int    `json:"remaining_today"`
}

func (s *Service) Status(ctx context.Context, family store.Family) (Status, error) {
	st := Status{Enabled: family.AiEnabled}
	if s.provider == nil {
		return st, nil
	}
	st.Configured, st.Provider, st.Model = true, s.provider.Name(), s.provider.Model()
	used, err := s.q.CountAIAnalysesSince(ctx, store.CountAIAnalysesSinceParams{FamilyID: family.ID, CreatedAt: s.now().Add(-limitWindow)})
	if err != nil {
		return Status{}, err
	}
	st.RemainingToday = max(0, s.dailyLimit-int(used))
	return st, nil
}

type AnalyzeInput struct {
	Family  store.Family
	BabyID  uuid.UUID
	UserID  uuid.UUID
	Days    int
	Locale  string
	Context Context
}

// Analyze returns an explanation of in.Context. Nothing is sent to a provider unless one
// is configured and the family enabled AI; an identical earlier question is answered
// from the database without a provider call.
func (s *Service) Analyze(ctx context.Context, in AnalyzeInput) (store.AiAnalysis, bool, error) {
	if s.provider == nil {
		return store.AiAnalysis{}, false, ErrNotConfigured
	}
	if !in.Family.AiEnabled {
		return store.AiAnalysis{}, false, ErrDisabled
	}
	contextJSON, err := json.Marshal(in.Context)
	if err != nil {
		return store.AiAnalysis{}, false, err
	}
	hash := sha256.Sum256(contextJSON)

	cached, err := s.q.FindAIAnalysis(ctx, store.FindAIAnalysisParams{
		FamilyID: in.Family.ID, ContextHash: hash[:], Locale: in.Locale,
		Provider: s.provider.Name(), Model: s.provider.Model(),
	})
	if err == nil {
		return cached, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return store.AiAnalysis{}, false, err
	}

	used, err := s.q.CountAIAnalysesSince(ctx, store.CountAIAnalysesSinceParams{FamilyID: in.Family.ID, CreatedAt: s.now().Add(-limitWindow)})
	if err != nil {
		return store.AiAnalysis{}, false, err
	}
	if int(used) >= s.dailyLimit {
		return store.AiAnalysis{}, false, errRateLimited
	}

	analysis, err := s.ask(ctx, BuildRequest(contextJSON, in.Locale), contextJSON)
	if err != nil {
		return store.AiAnalysis{}, false, err
	}
	result, err := json.Marshal(analysis)
	if err != nil {
		return store.AiAnalysis{}, false, err
	}
	row, err := s.q.InsertAIAnalysis(ctx, store.InsertAIAnalysisParams{
		ID: uuid.Must(uuid.NewV7()), FamilyID: in.Family.ID, BabyID: in.BabyID,
		Kind: in.Context.AnalysisKind, RangeDays: int32(in.Days), Locale: in.Locale,
		Provider: s.provider.Name(), Model: s.provider.Model(), ContextHash: hash[:],
		Result: result, CreatedBy: in.UserID, CreatedAt: s.now(),
	})
	return row, false, err
}

// ask calls the provider and validates the answer, retrying once. The log line carries
// sizes and outcomes only — never the context or the answer.
func (s *Service) ask(ctx context.Context, req Request, contextJSON []byte) (Analysis, error) {
	for attempt := 1; attempt <= attempts; attempt++ {
		started := time.Now()
		resp, err := s.provider.Analyze(ctx, req)
		attrs := []any{
			"provider", s.provider.Name(), "model", s.provider.Model(), "attempt", attempt,
			"duration_ms", time.Since(started).Milliseconds(),
			"input_tokens", resp.InputTokens, "output_tokens", resp.OutputTokens,
		}
		if err != nil {
			// A failed call is not retried here: the SDK already retried what is retryable.
			s.log.Warn("ai call failed", append(attrs, "error", err.Error())...)
			return Analysis{}, errUpstream
		}
		analysis, err := ParseAnalysis(resp.JSON, contextJSON)
		if err == nil {
			s.log.Info("ai call", append(attrs, "outcome", "ok")...)
			return analysis, nil
		}
		s.log.Warn("ai answer rejected", append(attrs, "reason", err.Error())...)
	}
	return Analysis{}, errUpstream
}

// List returns the most recent analyses of a baby.
func (s *Service) List(ctx context.Context, familyID, babyID uuid.UUID, limit int32) ([]store.AiAnalysis, error) {
	return s.q.ListAIAnalyses(ctx, store.ListAIAnalysesParams{FamilyID: familyID, BabyID: babyID, Limit: limit})
}

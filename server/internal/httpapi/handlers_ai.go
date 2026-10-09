package httpapi

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/google/uuid"

	"babytracker/internal/ai"
	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/predict"
	"babytracker/internal/stats"
	"babytracker/internal/store"
)

const (
	// A model may take a while; this replaces the server's default write deadline.
	aiWriteDeadline  = 3 * time.Minute
	analysesListSize = 20
)

type analysisDTO struct {
	ID        uuid.UUID       `json:"id"`
	BabyID    uuid.UUID       `json:"baby_id"`
	Kind      string          `json:"kind"`
	Days      int32           `json:"days"`
	Locale    string          `json:"locale"`
	Provider  string          `json:"provider"`
	Model     string          `json:"model"`
	CreatedAt time.Time       `json:"created_at"`
	Result    json.RawMessage `json:"result"`
	// Cached is true when an identical earlier answer was reused.
	Cached bool `json:"cached"`
}

func analysisFrom(a store.AiAnalysis, cached bool) analysisDTO {
	return analysisDTO{
		ID: a.ID, BabyID: a.BabyID, Kind: a.Kind, Days: a.RangeDays, Locale: a.Locale,
		Provider: a.Provider, Model: a.Model, CreatedAt: a.CreatedAt.UTC(), Result: a.Result, Cached: cached,
	}
}

func (s *server) handleAIStatus(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	family, err := s.q.GetFamily(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	status, err := s.AI.Status(r.Context(), family)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, status)
	return nil
}

func (s *server) babyByID(r *http.Request, c auth.Claims, id uuid.UUID) (store.Baby, error) {
	baby, err := s.q.GetBaby(r.Context(), store.GetBabyParams{ID: id, FamilyID: c.FamilyID})
	if err != nil {
		return store.Baby{}, notFoundIfNoRows(err)
	}
	if baby.DeletedAt != nil {
		return store.Baby{}, apperr.NotFound()
	}
	return baby, nil
}

func (s *server) handleListAnalyses(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	id, err := uuid.Parse(r.URL.Query().Get("baby_id"))
	if err != nil {
		return apperr.Validation(map[string]string{"baby_id": "invalid"})
	}
	baby, err := s.babyByID(r, c, id)
	if err != nil {
		return err
	}
	rows, err := s.AI.List(r.Context(), c.FamilyID, baby.ID, analysesListSize)
	if err != nil {
		return err
	}
	out := make([]analysisDTO, 0, len(rows))
	for _, row := range rows {
		out = append(out, analysisFrom(row, false))
	}
	writeJSON(w, http.StatusOK, map[string]any{"analyses": out})
	return nil
}

func (s *server) handleCreateAnalysis(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	var in struct {
		BabyID uuid.UUID `json:"baby_id"`
		Kind   string    `json:"kind"`
		Days   int       `json:"days"`
		Locale string    `json:"locale"`
	}
	if err := readJSON(w, r, &in); err != nil {
		return err
	}
	fields := map[string]string{}
	if !ai.ValidKind(in.Kind) {
		fields["kind"] = "invalid"
	}
	if in.Days != 7 && in.Days != 14 && in.Days != 30 {
		fields["days"] = "invalid"
	}
	if !ai.SupportedLocale(in.Locale) {
		fields["locale"] = "invalid"
	}
	if len(fields) > 0 {
		return apperr.Validation(fields)
	}
	baby, err := s.babyByID(r, c, in.BabyID)
	if err != nil {
		return err
	}
	family, err := s.q.GetFamily(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	// Consent is checked before any of the family's data is even read.
	status, err := s.AI.Status(r.Context(), family)
	if err != nil {
		return err
	}
	if !status.Configured {
		return ai.ErrNotConfigured
	}
	if !status.Enabled {
		return ai.ErrDisabled
	}

	loc, err := time.LoadLocation(family.Timezone)
	if err != nil {
		return err
	}
	now := s.Now()
	today := now.In(loc)
	from := time.Date(today.Year(), today.Month(), today.Day()-in.Days+1, 0, 0, 0, 0, loc)
	historyFrom := now.AddDate(0, 0, -predict.LookbackDays)
	if from.Before(historyFrom) {
		historyFrom = from
	}
	events, err := s.statsEvents(r, baby, historyFrom.Add(-statsLookback), now.Add(time.Second))
	if err != nil {
		return err
	}
	computed, err := stats.Compute(stats.Input{Timezone: family.Timezone, End: today.Format(time.DateOnly), Days: in.Days, Now: now, Events: events})
	if err != nil {
		return err
	}
	next, err := predict.NextSleep(predict.Input{Timezone: family.Timezone, Now: now, Events: events})
	if err != nil {
		return err
	}

	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(aiWriteDeadline))
	row, cached, err := s.AI.Analyze(r.Context(), ai.AnalyzeInput{
		Family: family, BabyID: baby.ID, UserID: c.UserID, Days: in.Days, Locale: in.Locale,
		Context: ai.BuildContext(in.Kind, baby.DateOfBirth, now, loc, computed, next),
	})
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, analysisFrom(row, cached))
	return nil
}

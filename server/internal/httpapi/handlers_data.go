package httpapi

import (
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"

	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/store"
	"babytracker/internal/syncsvc"
)

const (
	defaultEventsLimit = 100
	maxEventsLimit     = 500
)

func (s *server) handleGetFamily(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	family, err := s.q.GetFamily(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	users, err := s.q.ListFamilyUsers(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	members := make([]userDTO, 0, len(users))
	for _, u := range users {
		members = append(members, userFrom(u))
	}
	writeJSON(w, http.StatusOK, map[string]any{"family": syncsvc.FamilyFrom(family), "members": members})
	return nil
}

func (s *server) handlePatchFamily(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	var patch syncsvc.FamilyPatch
	if err := readJSON(w, r, &patch); err != nil {
		return err
	}
	family, err := s.Sync.UpdateFamily(r.Context(), c.FamilyID, patch)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]any{"family": syncsvc.FamilyFrom(family)})
	return nil
}

func (s *server) handleListBabies(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	babies, err := s.q.ListBabies(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	out := make([]syncsvc.BabyDTO, 0, len(babies))
	for _, b := range babies {
		out = append(out, syncsvc.BabyFrom(b))
	}
	writeJSON(w, http.StatusOK, map[string]any{"babies": out})
	return nil
}

func (s *server) handleGetBaby(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	baby, err := s.babyFromPath(r, c)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]any{"baby": syncsvc.BabyFrom(baby)})
	return nil
}

func (s *server) babyFromPath(r *http.Request, c auth.Claims) (store.Baby, error) {
	id, err := pathUUID(r, "id")
	if err != nil {
		return store.Baby{}, err
	}
	baby, err := s.q.GetBaby(r.Context(), store.GetBabyParams{ID: id, FamilyID: c.FamilyID})
	if err != nil {
		return store.Baby{}, notFoundIfNoRows(err)
	}
	if baby.DeletedAt != nil {
		return store.Baby{}, apperr.NotFound()
	}
	return baby, nil
}

func (s *server) handleListEvents(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	baby, err := s.babyFromPath(r, c)
	if err != nil {
		return err
	}
	query := r.URL.Query()
	fields := map[string]string{}
	params := store.ListEventsParams{
		FamilyID: c.FamilyID, BabyID: baby.ID,
		Types:          query["type"],
		IncludeDeleted: query.Get("include_deleted") == "true",
		FromTime:       queryTime(query.Get("from"), "from", fields),
		ToTime:         queryTime(query.Get("to"), "to", fields),
	}
	if params.Types == nil {
		params.Types = []string{}
	}
	limit := defaultEventsLimit
	if v := query.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > maxEventsLimit {
			fields["limit"] = "out_of_range"
		}
		limit = n
	}
	if token := query.Get("page_token"); token != "" {
		t, id, ok := decodePageToken(token)
		if !ok {
			fields["page_token"] = "invalid"
		}
		params.BeforeTime, params.BeforeID = &t, id
	}
	if len(fields) > 0 {
		return apperr.Validation(fields)
	}
	params.RowLimit = int32(limit + 1)

	events, err := s.q.ListEvents(r.Context(), params)
	if err != nil {
		return err
	}
	var next *string
	if len(events) > limit {
		events = events[:limit]
		last := events[limit-1]
		token := encodePageToken(last.StartedAt, last.ID)
		next = &token
	}
	out := make([]syncsvc.EventDTO, 0, len(events))
	for _, e := range events {
		out = append(out, syncsvc.EventFrom(e))
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": out, "next_page_token": next})
	return nil
}

func queryTime(value, key string, fields map[string]string) *time.Time {
	if value == "" {
		return nil
	}
	t, err := time.Parse(time.RFC3339, value)
	if err != nil {
		fields[key] = "invalid"
		return nil
	}
	return &t
}

func encodePageToken(t time.Time, id uuid.UUID) string {
	return base64.RawURLEncoding.EncodeToString([]byte(t.UTC().Format(time.RFC3339Nano) + "|" + id.String()))
}

func decodePageToken(token string) (time.Time, uuid.UUID, bool) {
	raw, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return time.Time{}, uuid.Nil, false
	}
	ts, idStr, ok := strings.Cut(string(raw), "|")
	if !ok {
		return time.Time{}, uuid.Nil, false
	}
	t, err := time.Parse(time.RFC3339Nano, ts)
	if err != nil {
		return time.Time{}, uuid.Nil, false
	}
	id, err := uuid.Parse(idStr)
	if err != nil {
		return time.Time{}, uuid.Nil, false
	}
	return t, id, true
}

func (s *server) handlePull(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	limit := 0
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > syncsvc.MaxPullLimit {
			return apperr.Validation(map[string]string{"limit": "out_of_range"})
		}
		limit = n
	}
	res, err := s.Sync.Pull(r.Context(), c.FamilyID, r.URL.Query().Get("cursor"), limit)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, res)
	return nil
}

func (s *server) handlePush(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	var in struct {
		Mutations []syncsvc.Mutation `json:"mutations"`
	}
	if err := readJSON(w, r, &in); err != nil {
		return err
	}
	results, err := s.Sync.Push(r.Context(), c, in.Mutations)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results, "server_time": s.Now().UTC()})
	return nil
}

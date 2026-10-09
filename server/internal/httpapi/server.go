// Package httpapi is the REST transport described in docs/API.md.
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/netip"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"

	"babytracker/internal/ai"
	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/store"
	"babytracker/internal/syncsvc"
)

const maxBodyBytes = 1 << 20

type Deps struct {
	Pool           *pgxpool.Pool
	Auth           *auth.Service
	Sync           *syncsvc.Service
	AI             *ai.Service
	Issuer         *auth.TokenIssuer
	Logger         *slog.Logger
	Now            func() time.Time
	RateLimits     RateLimits
	TrustedProxies []netip.Prefix
}

type server struct {
	Deps
	q              *store.Queries
	loginByIPEmail *keyedLimiter
	loginByIP      *keyedLimiter
	refreshByIP    *keyedLimiter
	redeemByIP     *keyedLimiter
}

// New returns the HTTP handler for the whole API.
func New(d Deps) http.Handler {
	s := &server{
		Deps:           d,
		q:              store.New(d.Pool),
		loginByIPEmail: newKeyedLimiter(d.RateLimits.LoginPerIPAndEmail),
		loginByIP:      newKeyedLimiter(d.RateLimits.LoginPerIP),
		refreshByIP:    newKeyedLimiter(d.RateLimits.RefreshPerIP),
		redeemByIP:     newKeyedLimiter(d.RateLimits.RedeemPerIP),
	}
	mux := http.NewServeMux()
	const v1 = "/api/v1"

	mux.HandleFunc("GET "+v1+"/healthz", s.handleHealth)
	mux.HandleFunc("GET "+v1+"/readyz", s.handleReady)

	mux.HandleFunc("POST "+v1+"/auth/login", s.public(s.handleLogin))
	mux.HandleFunc("POST "+v1+"/auth/refresh", s.public(s.handleRefresh))
	mux.HandleFunc("POST "+v1+"/auth/invites/redeem", s.public(s.handleRedeemInvite))
	mux.HandleFunc("POST "+v1+"/auth/logout", s.authed(s.handleLogout))
	mux.HandleFunc("POST "+v1+"/auth/password", s.authed(s.handleChangePassword))
	mux.HandleFunc("POST "+v1+"/auth/invites", s.authed(s.handleCreateInvite))

	mux.HandleFunc("GET "+v1+"/me", s.authed(s.handleMe))
	mux.HandleFunc("GET "+v1+"/devices", s.authed(s.handleListDevices))
	mux.HandleFunc("DELETE "+v1+"/devices/{id}", s.authed(s.handleRevokeDevice))
	mux.HandleFunc("GET "+v1+"/family", s.authed(s.handleGetFamily))
	mux.HandleFunc("PATCH "+v1+"/family", s.authed(s.handlePatchFamily))

	mux.HandleFunc("GET "+v1+"/babies", s.authed(s.handleListBabies))
	mux.HandleFunc("GET "+v1+"/babies/{id}", s.authed(s.handleGetBaby))
	mux.HandleFunc("GET "+v1+"/babies/{id}/events", s.authed(s.handleListEvents))
	mux.HandleFunc("GET "+v1+"/babies/{id}/stats", s.authed(s.handleStats))
	mux.HandleFunc("GET "+v1+"/babies/{id}/predictions/next-sleep", s.authed(s.handleNextSleep))

	mux.HandleFunc("GET "+v1+"/ai/status", s.authed(s.handleAIStatus))
	mux.HandleFunc("GET "+v1+"/ai/analyses", s.authed(s.handleListAnalyses))
	mux.HandleFunc("POST "+v1+"/ai/analyses", s.authed(s.handleCreateAnalysis))

	mux.HandleFunc("GET "+v1+"/sync", s.authed(s.handlePull))
	mux.HandleFunc("POST "+v1+"/sync/push", s.authed(s.handlePush))

	mux.HandleFunc("/", s.public(func(http.ResponseWriter, *http.Request) error { return apperr.NotFound() }))

	return s.withRequestInfo(mux)
}

// reqInfo is filled in as the request progresses and read by the access log.
type reqInfo struct {
	id       string
	userID   uuid.UUID
	familyID uuid.UUID
	errCode  string
}

type ctxKey struct{}

func infoFrom(ctx context.Context) *reqInfo {
	if ri, ok := ctx.Value(ctxKey{}).(*reqInfo); ok {
		return ri
	}
	return &reqInfo{}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

// Unwrap lets http.ResponseController reach the real writer (per-request deadlines).
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// withRequestInfo assigns a request ID, recovers from panics and writes the access log.
// The log carries IDs and outcomes only — never bodies, tokens or personal data.
func (s *server) withRequestInfo(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ri := &reqInfo{id: uuid.NewString()}
		w.Header().Set("X-Request-Id", ri.id)
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		r = r.WithContext(context.WithValue(r.Context(), ctxKey{}, ri))

		defer func() {
			if p := recover(); p != nil {
				s.Logger.Error("panic", "request_id", ri.id, "panic", p)
				s.writeError(rec, r, errors.New("panic"))
			}
			attrs := []any{
				"request_id", ri.id, "method", r.Method, "route", r.Pattern,
				"status", rec.status, "duration_ms", time.Since(start).Milliseconds(),
			}
			if ri.userID != uuid.Nil {
				attrs = append(attrs, "user_id", ri.userID, "family_id", ri.familyID)
			}
			if ri.errCode != "" {
				attrs = append(attrs, "error_code", ri.errCode)
			}
			s.Logger.Info("request", attrs...)
		}()
		next.ServeHTTP(rec, r)
	})
}

type handler func(w http.ResponseWriter, r *http.Request) error
type authedHandler func(w http.ResponseWriter, r *http.Request, c auth.Claims) error

func (s *server) public(h handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := h(w, r); err != nil {
			s.writeError(w, r, err)
		}
	}
}

// authed verifies the bearer token. The family and user always come from the token.
func (s *server) authed(h authedHandler) http.HandlerFunc {
	return s.public(func(w http.ResponseWriter, r *http.Request) error {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			return apperr.Unauthenticated()
		}
		claims, err := s.Issuer.Verify(token)
		if err != nil {
			return apperr.Unauthenticated()
		}
		ri := infoFrom(r.Context())
		ri.userID, ri.familyID = claims.UserID, claims.FamilyID
		return h(w, r, claims)
	})
}

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code    string            `json:"code"`
	Message string            `json:"message"`
	Fields  map[string]string `json:"fields,omitempty"`
}

func (s *server) writeError(w http.ResponseWriter, r *http.Request, err error) {
	ri := infoFrom(r.Context())
	ae, ok := apperr.As(err)
	if !ok {
		var tooLarge *http.MaxBytesError
		switch {
		case errors.As(err, &tooLarge):
			ae = apperr.New(http.StatusRequestEntityTooLarge, "payload_too_large")
		case errors.Is(err, context.Canceled):
			ae = apperr.New(499, "client_closed_request")
		default:
			s.Logger.Error("internal error", "request_id", ri.id, "error", err.Error())
			ae = apperr.New(http.StatusInternalServerError, "internal")
		}
	}
	ri.errCode = ae.Code
	if ae.Code == "rate_limited" {
		w.Header().Set("Retry-After", "60")
	}
	writeJSON(w, ae.Status, errorBody{Error: errorDetail{
		Code: ae.Code, Message: strings.ReplaceAll(ae.Code, "_", " "), Fields: ae.Fields,
	}})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// readJSON decodes exactly one JSON value, rejecting unknown fields and oversized bodies.
func readJSON(w http.ResponseWriter, r *http.Request, dst any) error {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return err
		}
		return apperr.InvalidRequest()
	}
	if dec.More() {
		return apperr.InvalidRequest()
	}
	return nil
}

func pathUUID(r *http.Request, name string) (uuid.UUID, error) {
	id, err := uuid.Parse(r.PathValue(name))
	if err != nil {
		// A malformed ID cannot exist, so it is simply not found.
		return uuid.Nil, apperr.NotFound()
	}
	return id, nil
}

func (s *server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *server) handleReady(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.Pool.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

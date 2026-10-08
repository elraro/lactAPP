package httpapi

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/store"
	"babytracker/internal/syncsvc"
)

var errRateLimited = apperr.New(http.StatusTooManyRequests, "rate_limited")

type userDTO struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	DisplayName string    `json:"display_name"`
}

func userFrom(u store.User) userDTO {
	return userDTO{ID: u.ID, Email: u.Email, DisplayName: u.DisplayName}
}

type deviceDTO struct {
	ID         uuid.UUID  `json:"id"`
	UserID     uuid.UUID  `json:"user_id"`
	Name       string     `json:"name"`
	Platform   string     `json:"platform"`
	AppVersion string     `json:"app_version"`
	CreatedAt  time.Time  `json:"created_at"`
	LastSeenAt time.Time  `json:"last_seen_at"`
	RevokedAt  *time.Time `json:"revoked_at"`
}

func deviceFrom(d store.Device) deviceDTO {
	out := deviceDTO{
		ID: d.ID, UserID: d.UserID, Name: d.Name, Platform: d.Platform, AppVersion: d.AppVersion,
		CreatedAt: d.CreatedAt.UTC(), LastSeenAt: d.LastSeenAt.UTC(),
	}
	if d.RevokedAt != nil {
		t := d.RevokedAt.UTC()
		out.RevokedAt = &t
	}
	return out
}

type sessionResponse struct {
	AccessToken  string            `json:"access_token"`
	ExpiresIn    int               `json:"expires_in"`
	RefreshToken string            `json:"refresh_token"`
	User         userDTO           `json:"user"`
	Family       syncsvc.FamilyDTO `json:"family"`
	ServerTime   time.Time         `json:"server_time"`
}

func (s *server) writeSession(w http.ResponseWriter, sess auth.Session) {
	writeJSON(w, http.StatusOK, sessionResponse{
		AccessToken: sess.AccessToken, ExpiresIn: sess.ExpiresIn, RefreshToken: sess.RefreshToken,
		User: userFrom(sess.User), Family: syncsvc.FamilyFrom(sess.Family), ServerTime: s.Now().UTC(),
	})
}

func (s *server) handleLogin(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Email    string          `json:"email"`
		Password string          `json:"password"`
		Device   auth.DeviceInfo `json:"device"`
	}
	if err := readJSON(w, r, &in); err != nil {
		return err
	}
	ip, now := clientIP(r, s.TrustedProxies), s.Now()
	emailKey := ip + "|" + strings.ToLower(strings.TrimSpace(in.Email))
	if !s.loginByIP.allow(ip, now) || !s.loginByIPEmail.allow(emailKey, now) {
		return errRateLimited
	}
	sess, err := s.Auth.Login(r.Context(), in.Email, in.Password, in.Device)
	if err != nil {
		return err
	}
	s.writeSession(w, sess)
	return nil
}

func (s *server) handleRefresh(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		RefreshToken string    `json:"refresh_token"`
		DeviceID     uuid.UUID `json:"device_id"`
	}
	if err := readJSON(w, r, &in); err != nil {
		return err
	}
	if !s.refreshByIP.allow(clientIP(r, s.TrustedProxies), s.Now()) {
		return errRateLimited
	}
	sess, err := s.Auth.Refresh(r.Context(), in.RefreshToken, in.DeviceID)
	if err != nil {
		return err
	}
	s.writeSession(w, sess)
	return nil
}

func (s *server) handleRedeemInvite(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Code        string          `json:"code"`
		Email       string          `json:"email"`
		DisplayName string          `json:"display_name"`
		Password    string          `json:"password"`
		Device      auth.DeviceInfo `json:"device"`
	}
	if err := readJSON(w, r, &in); err != nil {
		return err
	}
	if !s.redeemByIP.allow(clientIP(r, s.TrustedProxies), s.Now()) {
		return errRateLimited
	}
	sess, err := s.Auth.RedeemInvite(r.Context(), auth.RedeemInput{
		Code: in.Code, Email: in.Email, DisplayName: in.DisplayName, Password: in.Password, Device: in.Device,
	})
	if err != nil {
		return err
	}
	s.writeSession(w, sess)
	return nil
}

func (s *server) handleLogout(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	if err := s.Auth.Logout(r.Context(), c); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (s *server) handleChangePassword(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	var in struct {
		CurrentPassword string `json:"current_password"`
		NewPassword     string `json:"new_password"`
	}
	if err := readJSON(w, r, &in); err != nil {
		return err
	}
	if err := s.Auth.ChangePassword(r.Context(), c, in.CurrentPassword, in.NewPassword); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (s *server) handleCreateInvite(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	code, expiresAt, err := s.Auth.CreateInvite(r.Context(), c)
	if err != nil {
		return err
	}
	writeJSON(w, http.StatusCreated, map[string]any{"code": code, "expires_at": expiresAt.UTC()})
	return nil
}

func (s *server) handleMe(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	user, err := s.q.GetUser(r.Context(), c.UserID)
	if err != nil {
		return notFoundIfNoRows(err)
	}
	family, err := s.q.GetFamily(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	device, err := s.q.GetFamilyDevice(r.Context(), store.GetFamilyDeviceParams{ID: c.DeviceID, FamilyID: c.FamilyID})
	if err != nil {
		return notFoundIfNoRows(err)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"user": userFrom(user), "family": syncsvc.FamilyFrom(family), "device": deviceFrom(device),
	})
	return nil
}

func (s *server) handleListDevices(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	devices, err := s.q.ListFamilyDevices(r.Context(), c.FamilyID)
	if err != nil {
		return err
	}
	out := make([]deviceDTO, 0, len(devices))
	for _, d := range devices {
		out = append(out, deviceFrom(d))
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": out})
	return nil
}

// handleRevokeDevice signs a device out. Its current access token stays valid until it
// expires (at most the access-token TTL); it cannot be refreshed.
func (s *server) handleRevokeDevice(w http.ResponseWriter, r *http.Request, c auth.Claims) error {
	id, err := pathUUID(r, "id")
	if err != nil {
		return err
	}
	return store.InTx(r.Context(), s.Pool, pgx.TxOptions{}, func(q *store.Queries) error {
		rows, err := q.RevokeFamilyDevice(r.Context(), store.RevokeFamilyDeviceParams{ID: id, FamilyID: c.FamilyID})
		if err != nil {
			return err
		}
		if rows == 0 {
			return apperr.NotFound()
		}
		if err := q.RevokeDeviceTokens(r.Context(), id); err != nil {
			return err
		}
		w.WriteHeader(http.StatusNoContent)
		return nil
	})
}

func notFoundIfNoRows(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return apperr.NotFound()
	}
	return err
}

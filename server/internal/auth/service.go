package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"babytracker/internal/apperr"
	"babytracker/internal/store"
)

const (
	joinInviteTTL        = 48 * time.Hour
	setPasswordInviteTTL = 7 * 24 * time.Hour
	// refreshGrace is how long an already-rotated refresh token may be presented again
	// (lost response on a bad connection) before it is treated as theft.
	refreshGrace = 60 * time.Second
)

var (
	errInvalidCredentials = apperr.New(http.StatusUnauthorized, "invalid_credentials")
	errInvalidGrant       = apperr.New(http.StatusUnauthorized, "invalid_grant")
	errInviteExpired      = apperr.New(http.StatusGone, "invite_expired")
	errEmailTaken         = apperr.New(http.StatusConflict, "email_taken")
)

type Service struct {
	pool       *pgxpool.Pool
	issuer     *TokenIssuer
	refreshTTL time.Duration
	now        func() time.Time
	// dummyHash keeps login timing the same for unknown e-mails.
	dummyHash string
}

func NewService(pool *pgxpool.Pool, issuer *TokenIssuer, refreshTTL time.Duration, now func() time.Time) (*Service, error) {
	dummy, err := HashPassword(uuid.NewString())
	if err != nil {
		return nil, err
	}
	return &Service{pool: pool, issuer: issuer, refreshTTL: refreshTTL, now: now, dummyHash: dummy}, nil
}

// DeviceInfo is sent by the app with every sign-in.
type DeviceInfo struct {
	ID         uuid.UUID `json:"id"`
	Name       string    `json:"name"`
	Platform   string    `json:"platform"`
	AppVersion string    `json:"app_version"`
}

func (d DeviceInfo) validate(fields map[string]string) {
	if d.ID == uuid.Nil {
		fields["device.id"] = "required"
	}
	checkLen(fields, "device.name", d.Name, 1, 100)
	checkLen(fields, "device.platform", d.Platform, 1, 30)
	checkLen(fields, "device.app_version", d.AppVersion, 1, 30)
}

// Session is the result of a successful sign-in or refresh.
type Session struct {
	AccessToken  string
	ExpiresIn    int
	RefreshToken string
	User         store.User
	Family       store.Family
}

func (s *Service) Login(ctx context.Context, email, password string, dev DeviceInfo) (Session, error) {
	fields := map[string]string{}
	dev.validate(fields)
	if len(fields) > 0 {
		return Session{}, apperr.Validation(fields)
	}
	var sess Session
	err := store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		user, err := q.GetUserByEmail(ctx, normalizeEmail(email))
		if errors.Is(err, pgx.ErrNoRows) || (err == nil && (user.PasswordHash == nil || user.DisabledAt != nil)) {
			VerifyPassword(s.dummyHash, password)
			return errInvalidCredentials
		}
		if err != nil {
			return err
		}
		if !VerifyPassword(*user.PasswordHash, password) {
			return errInvalidCredentials
		}
		sess, err = s.startSession(ctx, q, user, dev)
		return err
	})
	return sess, err
}

// startSession registers the device for the user and issues a fresh token pair.
func (s *Service) startSession(ctx context.Context, q *store.Queries, user store.User, dev DeviceInfo) (Session, error) {
	existing, err := q.GetDeviceByID(ctx, dev.ID)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		err = q.InsertDevice(ctx, store.InsertDeviceParams{
			ID: dev.ID, FamilyID: user.FamilyID, UserID: user.ID,
			Name: dev.Name, Platform: dev.Platform, AppVersion: dev.AppVersion,
		})
	case err != nil:
	case existing.FamilyID != user.FamilyID:
		// A device ID is bound to the family that first used it.
		return Session{}, apperr.Validation(map[string]string{"device.id": "conflict"})
	default:
		if err = q.RevokeDeviceTokens(ctx, dev.ID); err == nil {
			err = q.UpdateDeviceOnLogin(ctx, store.UpdateDeviceOnLoginParams{
				ID: dev.ID, FamilyID: user.FamilyID, UserID: user.ID,
				Name: dev.Name, Platform: dev.Platform, AppVersion: dev.AppVersion,
			})
		}
	}
	if err != nil {
		return Session{}, err
	}
	return s.issue(ctx, q, user, dev.ID)
}

func (s *Service) issue(ctx context.Context, q *store.Queries, user store.User, deviceID uuid.UUID) (Session, error) {
	sess, _, err := s.issueWithID(ctx, q, user, deviceID)
	return sess, err
}

func (s *Service) issueWithID(ctx context.Context, q *store.Queries, user store.User, deviceID uuid.UUID) (Session, uuid.UUID, error) {
	family, err := q.GetFamily(ctx, user.FamilyID)
	if err != nil {
		return Session{}, uuid.Nil, err
	}
	access, err := s.issuer.Issue(Claims{UserID: user.ID, FamilyID: user.FamilyID, DeviceID: deviceID})
	if err != nil {
		return Session{}, uuid.Nil, err
	}
	refresh, hash, err := newRefreshToken()
	if err != nil {
		return Session{}, uuid.Nil, err
	}
	id := uuid.Must(uuid.NewV7())
	err = q.InsertRefreshToken(ctx, store.InsertRefreshTokenParams{
		ID: id, DeviceID: deviceID, UserID: user.ID, TokenHash: hash, ExpiresAt: s.now().Add(s.refreshTTL),
	})
	if err != nil {
		return Session{}, uuid.Nil, err
	}
	return Session{
		AccessToken:  access,
		ExpiresIn:    int(s.issuer.TTL() / time.Second),
		RefreshToken: refresh,
		User:         user,
		Family:       family,
	}, id, nil
}

// Refresh rotates a refresh token. Presenting a token that was already rotated more than
// refreshGrace ago revokes every token of the device.
func (s *Service) Refresh(ctx context.Context, refreshToken string, deviceID uuid.UUID) (Session, error) {
	var sess Session
	reuse := false
	err := store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		now := s.now()
		rt, err := q.GetRefreshTokenForUpdate(ctx, hashToken(refreshToken))
		if errors.Is(err, pgx.ErrNoRows) {
			return errInvalidGrant
		}
		if err != nil {
			return err
		}
		if rt.DeviceID != deviceID || rt.RevokedAt != nil || !now.Before(rt.ExpiresAt) {
			return errInvalidGrant
		}
		if rt.UsedAt != nil && now.Sub(*rt.UsedAt) > refreshGrace {
			reuse = true
			return q.RevokeDeviceTokens(ctx, rt.DeviceID)
		}
		device, err := q.GetDeviceByID(ctx, rt.DeviceID)
		if err != nil {
			return err
		}
		user, err := q.GetUser(ctx, rt.UserID)
		if err != nil {
			return err
		}
		if device.RevokedAt != nil || user.DisabledAt != nil {
			return errInvalidGrant
		}
		var newID uuid.UUID
		if sess, newID, err = s.issueWithID(ctx, q, user, rt.DeviceID); err != nil {
			return err
		}
		if err := q.MarkRefreshTokenUsed(ctx, store.MarkRefreshTokenUsedParams{ID: rt.ID, ReplacedBy: &newID, UsedAt: now}); err != nil {
			return err
		}
		return q.TouchDevice(ctx, rt.DeviceID)
	})
	if err != nil {
		return Session{}, err
	}
	if reuse {
		return Session{}, errInvalidGrant
	}
	return sess, nil
}

func (s *Service) Logout(ctx context.Context, c Claims) error {
	return store.New(s.pool).RevokeDeviceTokens(ctx, c.DeviceID)
}

// ChangePassword sets a new password and signs out the user's other devices.
func (s *Service) ChangePassword(ctx context.Context, c Claims, current, next string) error {
	if f := map[string]string{}; !checkPassword(f, "new_password", next) {
		return apperr.Validation(f)
	}
	hash, err := HashPassword(next)
	if err != nil {
		return err
	}
	return store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		user, err := q.GetUser(ctx, c.UserID)
		if err != nil {
			return err
		}
		if user.PasswordHash == nil || !VerifyPassword(*user.PasswordHash, current) {
			return errInvalidCredentials
		}
		if err := q.SetUserPassword(ctx, store.SetUserPasswordParams{ID: user.ID, PasswordHash: &hash}); err != nil {
			return err
		}
		return q.RevokeUserTokensExceptDevice(ctx, store.RevokeUserTokensExceptDeviceParams{UserID: user.ID, DeviceID: c.DeviceID})
	})
}

// CreateInvite returns a single-use code another parent can redeem to join the family.
func (s *Service) CreateInvite(ctx context.Context, c Claims) (code string, expiresAt time.Time, err error) {
	code, hash, err := newInviteCode()
	if err != nil {
		return "", time.Time{}, err
	}
	expiresAt = s.now().Add(joinInviteTTL)
	err = store.New(s.pool).InsertInvite(ctx, store.InsertInviteParams{
		ID: uuid.Must(uuid.NewV7()), FamilyID: c.FamilyID, CodeHash: hash,
		Purpose: "join_family", CreatedBy: &c.UserID, ExpiresAt: expiresAt,
	})
	return code, expiresAt, err
}

// RedeemInput is the body of POST /auth/invites/redeem. Email and DisplayName are only
// used by join codes; password-setup codes already identify the user.
type RedeemInput struct {
	Code        string
	Email       string
	DisplayName string
	Password    string
	Device      DeviceInfo
}

func (s *Service) RedeemInvite(ctx context.Context, in RedeemInput) (Session, error) {
	fields := map[string]string{}
	in.Device.validate(fields)
	checkPassword(fields, "password", in.Password)
	if len(fields) > 0 {
		return Session{}, apperr.Validation(fields)
	}
	hash, err := HashPassword(in.Password)
	if err != nil {
		return Session{}, err
	}
	var sess Session
	err = store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		inv, err := q.GetInviteForUpdate(ctx, hashInviteCode(in.Code))
		if errors.Is(err, pgx.ErrNoRows) {
			return errInviteExpired
		}
		if err != nil {
			return err
		}
		if inv.RedeemedAt != nil || !s.now().Before(inv.ExpiresAt) {
			return errInviteExpired
		}
		var user store.User
		if inv.Purpose == "set_password" {
			if user, err = q.GetUser(ctx, *inv.UserID); err != nil {
				return err
			}
			if err := q.SetUserPassword(ctx, store.SetUserPasswordParams{ID: user.ID, PasswordHash: &hash}); err != nil {
				return err
			}
			if err := q.RevokeUserTokens(ctx, user.ID); err != nil {
				return err
			}
		} else {
			email := normalizeEmail(in.Email)
			name := strings.TrimSpace(in.DisplayName)
			checkEmail(fields, "email", email)
			checkLen(fields, "display_name", name, 1, 100)
			if len(fields) > 0 {
				return apperr.Validation(fields)
			}
			if _, err := q.GetUserByEmail(ctx, email); err == nil {
				return errEmailTaken
			} else if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			user, err = q.CreateUser(ctx, store.CreateUserParams{
				ID: uuid.Must(uuid.NewV7()), FamilyID: inv.FamilyID, Email: email, DisplayName: name, PasswordHash: &hash,
			})
			if store.HasCode(err, store.CodeUniqueViolation) {
				return errEmailTaken
			}
			if err != nil {
				return err
			}
		}
		if err := q.RedeemInvite(ctx, inv.ID); err != nil {
			return err
		}
		sess, err = s.startSession(ctx, q, user, in.Device)
		return err
	})
	return sess, err
}

// CreateFamilyInput is used by the admin CLI; there is no HTTP endpoint for it.
type CreateFamilyInput struct {
	Name        string
	Timezone    string
	Email       string
	DisplayName string
}

// CreateFamily provisions a family with its first user and returns a one-time
// password-setup code for that user.
func (s *Service) CreateFamily(ctx context.Context, in CreateFamilyInput) (store.Family, store.User, string, error) {
	fields := map[string]string{}
	in.Name, in.DisplayName, in.Email = strings.TrimSpace(in.Name), strings.TrimSpace(in.DisplayName), normalizeEmail(in.Email)
	checkLen(fields, "name", in.Name, 1, 100)
	checkLen(fields, "display_name", in.DisplayName, 1, 100)
	checkEmail(fields, "email", in.Email)
	if !ValidTimezone(in.Timezone) {
		fields["timezone"] = "invalid"
	}
	if len(fields) > 0 {
		return store.Family{}, store.User{}, "", apperr.Validation(fields)
	}
	var (
		family store.Family
		user   store.User
		code   string
	)
	err := store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		var err error
		family, err = q.CreateFamily(ctx, store.CreateFamilyParams{
			ID: uuid.Must(uuid.NewV7()), Name: in.Name, Timezone: in.Timezone, SyncEpoch: uuid.New(),
		})
		if err != nil {
			return err
		}
		user, err = q.CreateUser(ctx, store.CreateUserParams{
			ID: uuid.Must(uuid.NewV7()), FamilyID: family.ID, Email: in.Email, DisplayName: in.DisplayName,
		})
		if store.HasCode(err, store.CodeUniqueViolation) {
			return errEmailTaken
		}
		if err != nil {
			return err
		}
		code, err = s.newSetPasswordInvite(ctx, q, user)
		return err
	})
	return family, user, code, err
}

// ResetPassword returns a one-time password-setup code for an existing user. The current
// password keeps working until the code is redeemed.
func (s *Service) ResetPassword(ctx context.Context, email string) (string, error) {
	var code string
	err := store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		user, err := q.GetUserByEmail(ctx, normalizeEmail(email))
		if errors.Is(err, pgx.ErrNoRows) {
			return apperr.NotFound()
		}
		if err != nil {
			return err
		}
		code, err = s.newSetPasswordInvite(ctx, q, user)
		return err
	})
	return code, err
}

func (s *Service) newSetPasswordInvite(ctx context.Context, q *store.Queries, user store.User) (string, error) {
	if err := q.ExpireUserSetPasswordInvites(ctx, &user.ID); err != nil {
		return "", err
	}
	code, hash, err := newInviteCode()
	if err != nil {
		return "", err
	}
	return code, q.InsertInvite(ctx, store.InsertInviteParams{
		ID: uuid.Must(uuid.NewV7()), FamilyID: user.FamilyID, CodeHash: hash,
		Purpose: "set_password", UserID: &user.ID, ExpiresAt: s.now().Add(setPasswordInviteTTL),
	})
}

func normalizeEmail(email string) string { return strings.ToLower(strings.TrimSpace(email)) }

// ValidTimezone reports whether tz is a loadable IANA zone ID.
func ValidTimezone(tz string) bool {
	if tz == "" || tz == "Local" {
		return false
	}
	_, err := time.LoadLocation(tz)
	return err == nil
}

func checkLen(fields map[string]string, key, value string, lo, hi int) {
	n := utf8.RuneCountInString(value)
	switch {
	case n == 0 && lo > 0:
		fields[key] = "required"
	case n < lo:
		fields[key] = "too_short"
	case n > hi:
		fields[key] = "too_long"
	}
}

func checkPassword(fields map[string]string, key, pw string) bool {
	checkLen(fields, key, pw, MinPasswordLen, MaxPasswordLen)
	return fields[key] == ""
}

func checkEmail(fields map[string]string, key, email string) {
	at := strings.LastIndex(email, "@")
	if email == "" {
		fields[key] = "required"
	} else if at < 1 || at == len(email)-1 || len(email) > 254 || strings.ContainsAny(email, " \t\r\n") {
		fields[key] = "invalid"
	}
}

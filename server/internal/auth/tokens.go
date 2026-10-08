package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base32"
	"encoding/base64"
	"errors"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// Claims identifies the caller of an authenticated request.
type Claims struct {
	UserID   uuid.UUID
	FamilyID uuid.UUID
	DeviceID uuid.UUID
}

// TokenIssuer signs and verifies short-lived access tokens (JWT, HS256).
type TokenIssuer struct {
	key []byte
	ttl time.Duration
	now func() time.Time
}

func NewTokenIssuer(key []byte, ttl time.Duration, now func() time.Time) *TokenIssuer {
	return &TokenIssuer{key: key, ttl: ttl, now: now}
}

func (t *TokenIssuer) TTL() time.Duration { return t.ttl }

type jwtClaims struct {
	Family string `json:"fam"`
	Device string `json:"dev"`
	jwt.RegisteredClaims
}

func (t *TokenIssuer) Issue(c Claims) (string, error) {
	now := t.now()
	return jwt.NewWithClaims(jwt.SigningMethodHS256, jwtClaims{
		Family: c.FamilyID.String(),
		Device: c.DeviceID.String(),
		RegisteredClaims: jwt.RegisteredClaims{
			Subject:   c.UserID.String(),
			IssuedAt:  jwt.NewNumericDate(now),
			ExpiresAt: jwt.NewNumericDate(now.Add(t.ttl)),
		},
	}).SignedString(t.key)
}

var errInvalidToken = errors.New("invalid token")

func (t *TokenIssuer) Verify(token string) (Claims, error) {
	var jc jwtClaims
	_, err := jwt.ParseWithClaims(token, &jc,
		func(*jwt.Token) (any, error) { return t.key, nil },
		jwt.WithValidMethods([]string{"HS256"}),
		jwt.WithExpirationRequired(),
		jwt.WithTimeFunc(t.now),
	)
	if err != nil {
		return Claims{}, errInvalidToken
	}
	var c Claims
	if c.UserID, err = uuid.Parse(jc.Subject); err != nil {
		return Claims{}, errInvalidToken
	}
	if c.FamilyID, err = uuid.Parse(jc.Family); err != nil {
		return Claims{}, errInvalidToken
	}
	if c.DeviceID, err = uuid.Parse(jc.Device); err != nil {
		return Claims{}, errInvalidToken
	}
	return c, nil
}

// newRefreshToken returns a 256-bit random token and the hash that is stored.
func newRefreshToken() (token string, hash []byte, err error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", nil, err
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	return token, hashToken(token), nil
}

func hashToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// Crockford base32: no I, L, O, U — unambiguous when read aloud or typed.
var inviteEncoding = base32.NewEncoding("0123456789ABCDEFGHJKMNPQRSTVWXYZ").WithPadding(base32.NoPadding)

// newInviteCode returns a 12-character code (60 bits) formatted XXXX-XXXX-XXXX.
func newInviteCode() (code string, hash []byte, err error) {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "", nil, err
	}
	raw := inviteEncoding.EncodeToString(b)[:12]
	code = raw[0:4] + "-" + raw[4:8] + "-" + raw[8:12]
	return code, hashInviteCode(code), nil
}

// hashInviteCode normalizes what a person may type (case, dashes, spaces, look-alike letters).
func hashInviteCode(code string) []byte {
	norm := strings.NewReplacer("-", "", " ", "", "O", "0", "I", "1", "L", "1").Replace(strings.ToUpper(code))
	return hashToken("invite:" + norm)
}

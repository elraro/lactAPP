package auth

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestPasswordHashing(t *testing.T) {
	hash, err := HashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(hash, "$argon2id$v=19$m=19456,t=2,p=1$") {
		t.Errorf("unexpected format: %s", hash)
	}
	if !VerifyPassword(hash, "correct horse battery") {
		t.Error("correct password rejected")
	}
	if VerifyPassword(hash, "correct horse batterY") {
		t.Error("wrong password accepted")
	}
	other, _ := HashPassword("correct horse battery")
	if other == hash {
		t.Error("two hashes of the same password are identical: salt is not random")
	}
	for _, bad := range []string{"", "plaintext", "$argon2id$v=19$m=1,t=1,p=1$$", "$bcrypt$x$y$z$w"} {
		if VerifyPassword(bad, "x") {
			t.Errorf("malformed hash %q accepted", bad)
		}
	}
}

func TestAccessToken(t *testing.T) {
	now := time.Date(2026, 10, 8, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	key := []byte(strings.Repeat("k", 32))
	issuer := NewTokenIssuer(key, 15*time.Minute, clock)
	want := Claims{UserID: uuid.New(), FamilyID: uuid.New(), DeviceID: uuid.New()}

	token, err := issuer.Issue(want)
	if err != nil {
		t.Fatal(err)
	}
	if got, err := issuer.Verify(token); err != nil || got != want {
		t.Fatalf("Verify = %+v, %v", got, err)
	}

	now = now.Add(15*time.Minute + time.Second)
	if _, err := issuer.Verify(token); err == nil {
		t.Error("expired token accepted")
	}
	now = now.Add(-time.Hour)

	otherKey := NewTokenIssuer([]byte(strings.Repeat("x", 32)), time.Hour, clock)
	if _, err := otherKey.Verify(token); err == nil {
		t.Error("token signed with another key accepted")
	}
	// "alg":"none" token with the same claims must not verify.
	parts := strings.Split(token, ".")
	none := "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0." + parts[1] + "."
	if _, err := issuer.Verify(none); err == nil {
		t.Error("unsigned token accepted")
	}
}

func TestInviteCode(t *testing.T) {
	code, hash, err := newInviteCode()
	if err != nil {
		t.Fatal(err)
	}
	if len(code) != 14 || code[4] != '-' || code[9] != '-' {
		t.Fatalf("unexpected code format %q", code)
	}
	typed := " " + strings.ToLower(strings.ReplaceAll(code, "-", " ")) + " "
	if !bytes.Equal(hashInviteCode(typed), hash) {
		t.Error("code typed in lower case with spaces does not match")
	}
	lookalike := strings.NewReplacer("0", "O", "1", "l").Replace(code)
	if !bytes.Equal(hashInviteCode(lookalike), hash) {
		t.Error("look-alike characters are not normalized")
	}
	if bytes.Equal(hashInviteCode("AAAA-AAAA-AAAA"), hash) {
		t.Error("different code matches")
	}
}

package httpapi_test

import (
	"context"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"

	"babytracker/internal/httpapi"
)

func TestSetupCodeLoginAndMe(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")

	status, body := a.do("GET", "/me", nil)
	if status != http.StatusOK {
		t.Fatalf("me: %d %v", status, body)
	}
	if got := body["user"].(map[string]any)["email"]; got != "a@example.com" {
		t.Errorf("email = %v", got)
	}
	if got := body["device"].(map[string]any)["id"]; got != a.deviceID {
		t.Errorf("device = %v, want %v", got, a.deviceID)
	}
	if fam := body["family"].(map[string]any); fam["timezone"] != "Europe/Madrid" || fam["ai_enabled"] != false {
		t.Errorf("family = %v", fam)
	}

	// The password chosen with the setup code works for a normal login on a second phone.
	second := e.login("A@Example.com ", testPassword)
	if second.userID != a.userID || second.familyID != a.familyID {
		t.Error("second device is not the same user and family")
	}
}

func TestLoginFailuresAreIndistinguishable(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	e.newFamily("a@example.com")
	anon := &client{e: e}

	for name, creds := range map[string][2]string{
		"wrong password": {"a@example.com", "not-the-password"},
		"unknown e-mail": {"nobody@example.com", testPassword},
	} {
		status, body := anon.do("POST", "/auth/login", map[string]any{
			"email": creds[0], "password": creds[1], "device": device(uuid.NewString()),
		})
		t.Run(name, func(t *testing.T) { expectError(t, status, body, http.StatusUnauthorized, "invalid_credentials") })
	}
}

func TestUserWithoutPasswordCannotLogIn(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	// Provisioned, setup code not redeemed yet.
	if _, _, _, err := e.auth.CreateFamily(context.Background(), authInput("pending@example.com")); err != nil {
		t.Fatal(err)
	}
	status, body := (&client{e: e}).do("POST", "/auth/login", map[string]any{
		"email": "pending@example.com", "password": "", "device": device(uuid.NewString()),
	})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_credentials")
}

func TestProtectedRoutesRequireValidToken(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	id := uuid.NewString()
	routes := [][2]string{
		{"GET", "/me"}, {"GET", "/devices"}, {"DELETE", "/devices/" + id},
		{"GET", "/family"}, {"PATCH", "/family"},
		{"GET", "/babies"}, {"GET", "/babies/" + id}, {"GET", "/babies/" + id + "/events"},
		{"GET", "/babies/" + id + "/stats"}, {"GET", "/babies/" + id + "/predictions/next-sleep"},
		{"GET", "/sync"}, {"POST", "/sync/push"},
		{"POST", "/auth/logout"}, {"POST", "/auth/password"}, {"POST", "/auth/invites"},
	}
	expired := *a
	e.clock.Advance(16 * time.Minute)
	for name, c := range map[string]*client{
		"no token":      {e: e},
		"garbage token": {e: e, access: "not.a.token"},
		"expired token": &expired,
	} {
		for _, r := range routes {
			status, body := c.do(r[0], r[1], nil)
			if status != http.StatusUnauthorized {
				t.Errorf("%s: %s %s = %d %v, want 401", name, r[0], r[1], status, body)
			}
		}
	}
}

func TestRefreshRotation(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	anon := &client{e: e}
	refresh := func(token string) (int, map[string]any) {
		return anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": token, "device_id": a.deviceID})
	}

	first := a.refresh
	status, body := refresh(first)
	if status != http.StatusOK {
		t.Fatalf("refresh: %d %v", status, body)
	}
	second := body["refresh_token"].(string)
	if second == first {
		t.Fatal("refresh token was not rotated")
	}
	a.access = body["access_token"].(string)
	if status, _ := a.do("GET", "/me", nil); status != http.StatusOK {
		t.Errorf("new access token rejected: %d", status)
	}

	// Lost response: the same token again within the grace window still works.
	e.clock.Advance(30 * time.Second)
	if status, body := refresh(first); status != http.StatusOK {
		t.Fatalf("retry within grace: %d %v", status, body)
	}

	// After the grace window the old token is treated as stolen: the device is signed out.
	e.clock.Advance(2 * time.Minute)
	status, body = refresh(first)
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")
	status, body = refresh(second)
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")
}

func TestRefreshRejections(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	anon := &client{e: e}

	status, body := anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": a.refresh, "device_id": uuid.NewString()})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")

	status, body = anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": "made-up", "device_id": a.deviceID})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")

	e.clock.Advance(91 * 24 * time.Hour)
	status, body = anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": a.refresh, "device_id": a.deviceID})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")
}

func TestLogoutRevokesRefreshToken(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	if status, body := a.do("POST", "/auth/logout", nil); status != http.StatusNoContent {
		t.Fatalf("logout: %d %v", status, body)
	}
	status, body := (&client{e: e}).do("POST", "/auth/refresh", map[string]any{"refresh_token": a.refresh, "device_id": a.deviceID})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")
}

func TestChangePassword(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	phone := e.newFamily("a@example.com")
	tablet := e.login("a@example.com", testPassword)
	anon := &client{e: e}
	const newPassword = "an-even-longer-password"

	status, body := phone.do("POST", "/auth/password", map[string]any{"current_password": "wrong", "new_password": newPassword})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_credentials")
	status, body = phone.do("POST", "/auth/password", map[string]any{"current_password": testPassword, "new_password": "short"})
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")

	if status, body := phone.do("POST", "/auth/password", map[string]any{"current_password": testPassword, "new_password": newPassword}); status != http.StatusNoContent {
		t.Fatalf("change password: %d %v", status, body)
	}

	// The other device is signed out; the one that changed the password is not.
	status, body = anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": tablet.refresh, "device_id": tablet.deviceID})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")
	if status, body := anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": phone.refresh, "device_id": phone.deviceID}); status != http.StatusOK {
		t.Errorf("current device was signed out: %d %v", status, body)
	}

	status, body = anon.do("POST", "/auth/login", map[string]any{"email": "a@example.com", "password": testPassword, "device": device(uuid.NewString())})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_credentials")
	e.login("a@example.com", newPassword)
}

func TestInviteSecondParent(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	baby := a.createBaby()
	b := a.join("b@example.com")

	if b.familyID != a.familyID || b.userID == a.userID {
		t.Fatalf("second parent: family %s user %s", b.familyID, b.userID)
	}
	if status, body := b.do("GET", "/babies/"+baby, nil); status != http.StatusOK {
		t.Errorf("second parent cannot see the baby: %d %v", status, body)
	}
	status, body := a.do("GET", "/family", nil)
	if members := body["members"].([]any); status != http.StatusOK || len(members) != 2 {
		t.Errorf("members = %v", body["members"])
	}
}

func TestInviteRules(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	anon := &client{e: e}
	newCode := func() string {
		_, body := a.do("POST", "/auth/invites", nil)
		return body["code"].(string)
	}
	redeem := func(code, email, password string) (int, map[string]any) {
		return anon.do("POST", "/auth/invites/redeem", map[string]any{
			"code": code, "email": email, "display_name": "B", "password": password, "device": device(uuid.NewString()),
		})
	}

	status, body := redeem("AAAA-BBBB-CCCC", "b@example.com", testPassword)
	expectError(t, status, body, http.StatusGone, "invite_expired")

	code := newCode()
	status, body = redeem(code, "a@example.com", testPassword)
	expectError(t, status, body, http.StatusConflict, "email_taken")
	status, body = redeem(code, "b@example.com", "short")
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
	status, body = redeem(code, "not-an-email", testPassword)
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")

	// Failed attempts did not consume the code.
	if status, body := redeem(code, "b@example.com", testPassword); status != http.StatusOK {
		t.Fatalf("redeem: %d %v", status, body)
	}
	status, body = redeem(code, "c@example.com", testPassword)
	expectError(t, status, body, http.StatusGone, "invite_expired")

	old := newCode()
	e.clock.Advance(49 * time.Hour)
	status, body = redeem(old, "d@example.com", testPassword)
	expectError(t, status, body, http.StatusGone, "invite_expired")
}

func TestResetPasswordCode(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	anon := &client{e: e}
	code, err := e.auth.ResetPassword(context.Background(), "a@example.com")
	if err != nil {
		t.Fatal(err)
	}
	const newPassword = "brand-new-password"
	body := map[string]any{"code": code, "password": newPassword, "device": device(uuid.NewString())}
	if status, resp := anon.do("POST", "/auth/invites/redeem", body); status != http.StatusOK {
		t.Fatalf("redeem: %d %v", status, resp)
	}
	status, resp := anon.do("POST", "/auth/invites/redeem", body)
	expectError(t, status, resp, http.StatusGone, "invite_expired")

	// Every existing session of the user is ended.
	status, resp = anon.do("POST", "/auth/refresh", map[string]any{"refresh_token": a.refresh, "device_id": a.deviceID})
	expectError(t, status, resp, http.StatusUnauthorized, "invalid_grant")
	e.login("a@example.com", newPassword)
}

func TestLoginRateLimit(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{LoginPerIPAndEmail: 5, LoginPerIP: 20})
	e.newFamily("a@example.com")
	anon := &client{e: e}
	attempt := func(email string) (int, map[string]any) {
		return anon.do("POST", "/auth/login", map[string]any{"email": email, "password": "wrong-password", "device": device(uuid.NewString())})
	}
	for i := 0; i < 5; i++ {
		status, body := attempt("a@example.com")
		expectError(t, status, body, http.StatusUnauthorized, "invalid_credentials")
	}
	status, body := attempt("a@example.com")
	expectError(t, status, body, http.StatusTooManyRequests, "rate_limited")

	// Even the right password is refused while limited, and another e-mail is unaffected.
	status, body = anon.do("POST", "/auth/login", map[string]any{"email": "a@example.com", "password": testPassword, "device": device(uuid.NewString())})
	expectError(t, status, body, http.StatusTooManyRequests, "rate_limited")
	status, body = attempt("other@example.com")
	expectError(t, status, body, http.StatusUnauthorized, "invalid_credentials")

	e.clock.Advance(time.Minute)
	e.login("a@example.com", testPassword)
}

func TestRevokeDevice(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	phone := e.newFamily("a@example.com")
	lost := e.login("a@example.com", testPassword)
	stranger := e.newFamily("z@example.com")

	status, body := stranger.do("DELETE", "/devices/"+lost.deviceID, nil)
	expectError(t, status, body, http.StatusNotFound, "not_found")

	if status, body := phone.do("DELETE", "/devices/"+lost.deviceID, nil); status != http.StatusNoContent {
		t.Fatalf("revoke: %d %v", status, body)
	}
	status, body = (&client{e: e}).do("POST", "/auth/refresh", map[string]any{"refresh_token": lost.refresh, "device_id": lost.deviceID})
	expectError(t, status, body, http.StatusUnauthorized, "invalid_grant")

	status, body = phone.do("GET", "/devices", nil)
	if devices := body["devices"].([]any); status != http.StatusOK || len(devices) != 2 {
		t.Errorf("devices = %v", body)
	}
	_, body = stranger.do("GET", "/devices", nil)
	if devices := body["devices"].([]any); len(devices) != 1 {
		t.Errorf("stranger sees %d devices, want 1", len(devices))
	}
}

func TestDeviceIDIsBoundToItsFamily(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")
	e.newFamily("z@example.com")
	status, body := (&client{e: e}).do("POST", "/auth/login", map[string]any{
		"email": "z@example.com", "password": testPassword, "device": device(a.deviceID),
	})
	expectError(t, status, body, http.StatusBadRequest, "validation_failed")
	// The original owner's session is untouched.
	if status, body := (&client{e: e}).do("POST", "/auth/refresh", map[string]any{"refresh_token": a.refresh, "device_id": a.deviceID}); status != http.StatusOK {
		t.Errorf("owner's session was affected: %d %v", status, body)
	}
}

func TestRequestHygiene(t *testing.T) {
	e := newEnv(t, httpapi.RateLimits{})
	a := e.newFamily("a@example.com")

	status, body := a.do("GET", "/nope", nil)
	expectError(t, status, body, http.StatusNotFound, "not_found")

	status, body = a.do("PATCH", "/family", map[string]any{"name": "X", "family_id": uuid.NewString()})
	expectError(t, status, body, http.StatusBadRequest, "invalid_request")

	status, body = a.do("POST", "/sync/push", []byte(`{"mutations": [`))
	expectError(t, status, body, http.StatusBadRequest, "invalid_request")

	big := append([]byte(`{"mutations":[],"pad":"`), make([]byte, 2<<20)...)
	for i := 23; i < len(big); i++ {
		big[i] = 'x'
	}
	status, body = a.do("POST", "/sync/push", big)
	expectError(t, status, body, http.StatusRequestEntityTooLarge, "payload_too_large")

	for _, path := range []string{"/healthz", "/readyz"} {
		if status, _ := (&client{e: e}).do("GET", path, nil); status != http.StatusOK {
			t.Errorf("%s = %d", path, status)
		}
	}
}

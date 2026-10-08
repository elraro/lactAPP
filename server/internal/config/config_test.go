package config

import (
	"strings"
	"testing"
	"time"
)

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestLoadDefaults(t *testing.T) {
	c, err := Load(env(map[string]string{
		"BT_DATABASE_URL":    "postgres://x",
		"BT_JWT_SIGNING_KEY": strings.Repeat("k", 32),
		"BT_TRUSTED_PROXIES": "10.0.0.0/8, 192.168.1.5",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if c.AccessTokenTTL != 15*time.Minute || c.RefreshTokenTTL != 90*24*time.Hour || c.HTTPAddr != ":8080" {
		t.Errorf("unexpected defaults: %+v", c)
	}
	if len(c.TrustedProxies) != 2 || c.TrustedProxies[1].Bits() != 32 {
		t.Errorf("trusted proxies = %v", c.TrustedProxies)
	}
}

func TestLoadReportsAllProblems(t *testing.T) {
	_, err := Load(env(map[string]string{
		"BT_ENV":              "staging",
		"BT_JWT_SIGNING_KEY":  "short",
		"BT_ACCESS_TOKEN_TTL": "soon",
		"BT_TRUSTED_PROXIES":  "not-an-ip",
	}))
	if err == nil {
		t.Fatal("expected error")
	}
	for _, want := range []string{"BT_ENV", "BT_DATABASE_URL", "BT_JWT_SIGNING_KEY", "BT_ACCESS_TOKEN_TTL", "BT_TRUSTED_PROXIES"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error does not mention %s: %v", want, err)
		}
	}
}

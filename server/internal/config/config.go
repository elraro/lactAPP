// Package config loads and validates the server configuration from BT_* environment variables.
package config

import (
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"time"
)

type Config struct {
	Env             string
	HTTPAddr        string
	DatabaseURL     string
	LogLevel        string
	JWTSigningKey   []byte
	AccessTokenTTL  time.Duration
	RefreshTokenTTL time.Duration
	TrustedProxies  []netip.Prefix
}

func (c Config) Production() bool { return c.Env == "production" }

// Load reads the configuration through getenv (os.Getenv in production).
// It reports every problem at once so a misconfigured deployment is fixed in one pass.
func Load(getenv func(string) string) (Config, error) {
	var errs []error
	get := func(key, def string) string {
		if v := strings.TrimSpace(getenv(key)); v != "" {
			return v
		}
		return def
	}
	duration := func(key, def string) time.Duration {
		d, err := time.ParseDuration(get(key, def))
		if err != nil || d <= 0 {
			errs = append(errs, fmt.Errorf("%s: invalid duration", key))
		}
		return d
	}

	c := Config{
		Env:             get("BT_ENV", "development"),
		HTTPAddr:        get("BT_HTTP_ADDR", ":8080"),
		DatabaseURL:     get("BT_DATABASE_URL", ""),
		LogLevel:        get("BT_LOG_LEVEL", "info"),
		JWTSigningKey:   []byte(get("BT_JWT_SIGNING_KEY", "")),
		AccessTokenTTL:  duration("BT_ACCESS_TOKEN_TTL", "15m"),
		RefreshTokenTTL: duration("BT_REFRESH_TOKEN_TTL", "2160h"),
	}
	if c.Env != "development" && c.Env != "production" {
		errs = append(errs, errors.New("BT_ENV: must be development or production"))
	}
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("BT_DATABASE_URL: required"))
	}
	if len(c.JWTSigningKey) < 32 {
		errs = append(errs, errors.New("BT_JWT_SIGNING_KEY: required, at least 32 characters"))
	}
	switch c.LogLevel {
	case "debug", "info", "warn", "error":
	default:
		errs = append(errs, errors.New("BT_LOG_LEVEL: must be debug, info, warn or error"))
	}
	for _, s := range strings.Split(get("BT_TRUSTED_PROXIES", ""), ",") {
		if s = strings.TrimSpace(s); s == "" {
			continue
		}
		p, err := netip.ParsePrefix(s)
		if err != nil {
			if addr, aerr := netip.ParseAddr(s); aerr == nil {
				p, err = netip.PrefixFrom(addr, addr.BitLen()), nil
			}
		}
		if err != nil {
			errs = append(errs, fmt.Errorf("BT_TRUSTED_PROXIES: %q is not an IP or CIDR", s))
			continue
		}
		c.TrustedProxies = append(c.TrustedProxies, p)
	}
	return c, errors.Join(errs...)
}

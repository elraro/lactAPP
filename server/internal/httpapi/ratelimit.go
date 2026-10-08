package httpapi

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// RateLimits are requests per minute; zero disables a limit.
type RateLimits struct {
	LoginPerIPAndEmail int
	LoginPerIP         int
	RefreshPerIP       int
	RedeemPerIP        int
}

// DefaultRateLimits are the values documented in docs/API.md §8.
var DefaultRateLimits = RateLimits{LoginPerIPAndEmail: 5, LoginPerIP: 20, RefreshPerIP: 30, RedeemPerIP: 5}

// keyedLimiter is an in-memory token bucket per key. State is per process, which is
// acceptable for the single-instance deployment this server is designed for.
type keyedLimiter struct {
	perMinute int
	mu        sync.Mutex
	buckets   map[string]*bucket
}

type bucket struct {
	lim  *rate.Limiter
	seen time.Time
}

const limiterMaxKeys = 10000

func newKeyedLimiter(perMinute int) *keyedLimiter {
	return &keyedLimiter{perMinute: perMinute, buckets: map[string]*bucket{}}
}

func (l *keyedLimiter) allow(key string, now time.Time) bool {
	if l.perMinute <= 0 {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.buckets) >= limiterMaxKeys {
		for k, b := range l.buckets {
			if now.Sub(b.seen) > 2*time.Minute {
				delete(l.buckets, k)
			}
		}
	}
	b, ok := l.buckets[key]
	if !ok {
		b = &bucket{lim: rate.NewLimiter(rate.Limit(float64(l.perMinute)/60), l.perMinute)}
		l.buckets[key] = b
	}
	b.seen = now
	return b.lim.AllowN(now, 1)
}

// clientIP returns the peer address, or the right-most X-Forwarded-For entry that is not
// a trusted proxy when the peer itself is one.
func clientIP(r *http.Request, trusted []netip.Prefix) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	peer, err := netip.ParseAddr(host)
	if err != nil || !isTrusted(peer, trusted) {
		return host
	}
	hops := strings.Split(strings.Join(r.Header.Values("X-Forwarded-For"), ","), ",")
	for i := len(hops) - 1; i >= 0; i-- {
		addr, err := netip.ParseAddr(strings.TrimSpace(hops[i]))
		if err != nil {
			break
		}
		if !isTrusted(addr, trusted) {
			return addr.String()
		}
	}
	return host
}

func isTrusted(addr netip.Addr, trusted []netip.Prefix) bool {
	addr = addr.Unmap()
	for _, p := range trusted {
		if p.Contains(addr) {
			return true
		}
	}
	return false
}

package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	// A device offline for longer than this may see a replayed *edit* applied again; creates
	// stay idempotent through their primary key (docs/SYNC.md §4).
	MutationRetention = 90 * 24 * time.Hour
	// Dead tokens and codes are kept this long for reuse detection and troubleshooting.
	CredentialRetention = 30 * 24 * time.Hour
)

// Pruned reports what one housekeeping run removed.
type Pruned struct {
	Mutations     int64
	RefreshTokens int64
	Invites       int64
}

// Prune deletes bookkeeping rows that are past their retention. It never touches family
// data: events, babies and their tombstones are kept forever.
func Prune(ctx context.Context, pool *pgxpool.Pool, now time.Time) (Pruned, error) {
	var out Pruned
	err := InTx(ctx, pool, pgx.TxOptions{}, func(q *Queries) error {
		var err error
		if out.Mutations, err = q.DeleteOldAppliedMutations(ctx, now.Add(-MutationRetention)); err != nil {
			return err
		}
		cutoff := now.Add(-CredentialRetention)
		if err = q.UnlinkOldRefreshTokens(ctx, cutoff); err != nil {
			return err
		}
		if out.RefreshTokens, err = q.DeleteOldRefreshTokens(ctx, cutoff); err != nil {
			return err
		}
		out.Invites, err = q.DeleteOldInvites(ctx, cutoff)
		return err
	})
	return out, err
}

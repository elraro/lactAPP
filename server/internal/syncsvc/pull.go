package syncsvc

import (
	"context"
	"encoding/base64"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"babytracker/internal/apperr"
	"babytracker/internal/store"
)

const (
	DefaultPullLimit = 500
	MaxPullLimit     = 500
	MaxPushMutations = 200

	cursorVersion = "1"
)

type Service struct {
	pool *pgxpool.Pool
	now  func() time.Time
}

func NewService(pool *pgxpool.Pool, now func() time.Time) *Service {
	return &Service{pool: pool, now: now}
}

// Change is one row of the feed with its latest state.
type Change struct {
	Entity string `json:"entity"`
	Data   any    `json:"data"`
	seq    int64
}

type PullResult struct {
	Changes    []Change  `json:"changes"`
	NextCursor string    `json:"next_cursor"`
	HasMore    bool      `json:"has_more"`
	ServerTime time.Time `json:"server_time"`
}

func encodeCursor(epoch uuid.UUID, seq int64) string {
	return base64.RawURLEncoding.EncodeToString(fmt.Appendf(nil, "%s:%s:%d", cursorVersion, epoch, seq))
}

func decodeCursor(cursor string) (epoch uuid.UUID, seq int64, err error) {
	raw, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return uuid.Nil, 0, err
	}
	parts := strings.Split(string(raw), ":")
	if len(parts) != 3 || parts[0] != cursorVersion {
		return uuid.Nil, 0, fmt.Errorf("malformed cursor")
	}
	if epoch, err = uuid.Parse(parts[1]); err != nil {
		return uuid.Nil, 0, err
	}
	if seq, err = strconv.ParseInt(parts[2], 10, 64); err != nil || seq < 0 {
		return uuid.Nil, 0, fmt.Errorf("malformed cursor")
	}
	return epoch, seq, nil
}

// Pull returns the rows of the family changed after cursor, oldest change first.
// An empty cursor starts from the beginning.
func (s *Service) Pull(ctx context.Context, familyID uuid.UUID, cursor string, limit int) (PullResult, error) {
	if limit <= 0 || limit > MaxPullLimit {
		limit = DefaultPullLimit
	}
	var (
		after       int64
		cursorEpoch uuid.UUID
		err         error
	)
	if cursor != "" {
		if cursorEpoch, after, err = decodeCursor(cursor); err != nil {
			return PullResult{}, apperr.InvalidRequest()
		}
	}

	var changes []Change
	var epoch uuid.UUID
	// One snapshot for all three tables. Writers of a family are serialized by the family
	// row lock, so a snapshot always contains a gap-free prefix of the sequence.
	opts := pgx.TxOptions{IsoLevel: pgx.RepeatableRead, AccessMode: pgx.ReadOnly}
	err = store.InTx(ctx, s.pool, opts, func(q *store.Queries) error {
		family, err := q.GetFamily(ctx, familyID)
		if err != nil {
			return err
		}
		epoch = family.SyncEpoch
		if cursor != "" && cursorEpoch != epoch {
			return apperr.New(http.StatusGone, "cursor_expired")
		}
		families, err := q.ListFamilyChanges(ctx, store.ListFamilyChangesParams{ID: familyID, Seq: after})
		if err != nil {
			return err
		}
		for _, f := range families {
			changes = append(changes, Change{Entity: "family", Data: FamilyFrom(f), seq: f.Seq})
		}
		babies, err := q.ListBabyChanges(ctx, store.ListBabyChangesParams{FamilyID: familyID, Seq: after, Limit: int32(limit + 1)})
		if err != nil {
			return err
		}
		for _, b := range babies {
			changes = append(changes, Change{Entity: "baby", Data: BabyFrom(b), seq: b.Seq})
		}
		events, err := q.ListEventChanges(ctx, store.ListEventChangesParams{FamilyID: familyID, Seq: after, Limit: int32(limit + 1)})
		if err != nil {
			return err
		}
		for _, e := range events {
			changes = append(changes, Change{Entity: "event", Data: EventFrom(e), seq: e.Seq})
		}
		return nil
	})
	if err != nil {
		return PullResult{}, err
	}

	sort.Slice(changes, func(i, j int) bool { return changes[i].seq < changes[j].seq })
	res := PullResult{Changes: changes, ServerTime: s.now().UTC()}
	if len(changes) > limit {
		res.Changes, res.HasMore = changes[:limit], true
	}
	if n := len(res.Changes); n > 0 {
		after = res.Changes[n-1].seq
	} else {
		res.Changes = []Change{}
	}
	res.NextCursor = encodeCursor(epoch, after)
	return res, nil
}

// FamilyPatch holds the optional fields of PATCH /family.
type FamilyPatch struct {
	Name      *string `json:"name"`
	Timezone  *string `json:"timezone"`
	AIEnabled *bool   `json:"ai_enabled"`
}

// UpdateFamily changes family settings and publishes the change in the feed.
func (s *Service) UpdateFamily(ctx context.Context, familyID uuid.UUID, p FamilyPatch) (store.Family, error) {
	var out store.Family
	err := store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		family, err := q.LockFamily(ctx, familyID)
		if err != nil {
			return err
		}
		errs := map[string]string{}
		if p.Name != nil {
			family.Name = strings.TrimSpace(*p.Name)
			if n := len([]rune(family.Name)); n == 0 {
				errs["name"] = "required"
			} else if n > 100 {
				errs["name"] = "too_long"
			}
		}
		if p.Timezone != nil {
			family.Timezone = *p.Timezone
			if !validTimezone(family.Timezone) {
				errs["timezone"] = "invalid"
			}
		}
		if p.AIEnabled != nil {
			family.AiEnabled = *p.AIEnabled
		}
		if len(errs) > 0 {
			return apperr.Validation(errs)
		}
		out, err = q.UpdateFamilySettings(ctx, store.UpdateFamilySettingsParams{
			ID: familyID, Name: family.Name, Timezone: family.Timezone, AiEnabled: family.AiEnabled,
			SyncSeq: family.SyncSeq + 1,
		})
		return err
	})
	return out, err
}

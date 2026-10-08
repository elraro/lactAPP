package syncsvc

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"babytracker/internal/apperr"
	"babytracker/internal/auth"
	"babytracker/internal/store"
)

var validTimezone = auth.ValidTimezone

// Mutation is one client-identified change. Fields holds only what the user changed.
type Mutation struct {
	MutationID  uuid.UUID                  `json:"mutation_id"`
	Entity      string                     `json:"entity"`
	EntityID    uuid.UUID                  `json:"entity_id"`
	Op          string                     `json:"op"`
	BaseVersion int32                      `json:"base_version"`
	Fields      map[string]json.RawMessage `json:"fields"`
}

const (
	StatusApplied  = "applied"
	StatusRejected = "rejected"
)

type Result struct {
	MutationID uuid.UUID         `json:"mutation_id"`
	Status     string            `json:"status"`
	ErrorCode  string            `json:"error_code,omitempty"`
	Fields     map[string]string `json:"fields,omitempty"`
	Merged     bool              `json:"merged"`
	Data       any               `json:"data,omitempty"`
}

// outcome is what applying a mutation decided, before it is recorded.
type outcome struct {
	errorCode string // empty = applied
	fields    map[string]string
	merged    bool
	wrote     bool // a row was written with the allocated seq
	data      any
}

func rejected(code string, fields map[string]string) outcome {
	return outcome{errorCode: code, fields: fields}
}

// Push applies mutations in order, each in its own transaction, so one failure never
// blocks the rest. Every outcome is recorded under its mutation_id; a replay returns
// the recorded outcome instead of applying the change again.
func (s *Service) Push(ctx context.Context, actor auth.Claims, mutations []Mutation) ([]Result, error) {
	if len(mutations) > MaxPushMutations {
		return nil, apperr.Validation(map[string]string{"mutations": "too_many"})
	}
	for _, m := range mutations {
		if m.MutationID == uuid.Nil || m.EntityID == uuid.Nil {
			return nil, apperr.InvalidRequest()
		}
	}
	results := make([]Result, 0, len(mutations))
	for _, m := range mutations {
		res, err := s.pushOne(ctx, actor, m)
		if err != nil {
			return nil, err
		}
		results = append(results, res)
	}
	return results, nil
}

func (s *Service) pushOne(ctx context.Context, actor auth.Claims, m Mutation) (Result, error) {
	res, err := s.applyInTx(ctx, actor, m, false)
	// Validation in Go mirrors the schema, so a constraint error here means the two have
	// drifted. Reject the mutation instead of failing the whole batch forever.
	if store.HasCode(err, store.CodeCheckViolation, store.CodeForeignKeyViolation) {
		return s.applyInTx(ctx, actor, m, true)
	}
	return res, err
}

func (s *Service) applyInTx(ctx context.Context, actor auth.Claims, m Mutation, forceReject bool) (Result, error) {
	var res Result
	err := store.InTx(ctx, s.pool, pgx.TxOptions{}, func(q *store.Queries) error {
		family, err := q.LockFamily(ctx, actor.FamilyID)
		if err != nil {
			return err
		}
		prev, err := q.GetAppliedMutation(ctx, store.GetAppliedMutationParams{FamilyID: actor.FamilyID, MutationID: m.MutationID})
		if err == nil {
			res, err = s.replay(ctx, q, actor.FamilyID, prev)
			return err
		}
		if !errors.Is(err, pgx.ErrNoRows) {
			return err
		}

		seq := family.SyncSeq + 1
		var out outcome
		if forceReject {
			out = rejected("validation_failed", nil)
		} else if out, err = s.apply(ctx, q, actor, seq, m); err != nil {
			return err
		}
		if out.wrote {
			if err := q.SetFamilySyncSeq(ctx, store.SetFamilySyncSeqParams{ID: family.ID, SyncSeq: seq}); err != nil {
				return err
			}
		}
		rec := store.InsertAppliedMutationParams{
			FamilyID: actor.FamilyID, MutationID: m.MutationID, DeviceID: actor.DeviceID,
			Entity: m.Entity, EntityID: m.EntityID, Status: StatusApplied, Merged: out.merged,
		}
		res = Result{MutationID: m.MutationID, Status: StatusApplied, Merged: out.merged, Data: out.data}
		if out.errorCode != "" {
			rec.Status, rec.ErrorCode = StatusRejected, &out.errorCode
			res = Result{MutationID: m.MutationID, Status: StatusRejected, ErrorCode: out.errorCode, Fields: out.fields}
		}
		return q.InsertAppliedMutation(ctx, rec)
	})
	return res, err
}

// replay rebuilds the result of a mutation that was already processed.
func (s *Service) replay(ctx context.Context, q *store.Queries, familyID uuid.UUID, prev store.AppliedMutation) (Result, error) {
	res := Result{MutationID: prev.MutationID, Status: prev.Status, Merged: prev.Merged}
	if prev.ErrorCode != nil {
		res.ErrorCode = *prev.ErrorCode
		return res, nil
	}
	var err error
	switch prev.Entity {
	case "event":
		var e store.Event
		if e, err = q.GetEvent(ctx, store.GetEventParams{ID: prev.EntityID, FamilyID: familyID}); err == nil {
			res.Data = EventFrom(e)
		}
	case "baby":
		var b store.Baby
		if b, err = q.GetBaby(ctx, store.GetBabyParams{ID: prev.EntityID, FamilyID: familyID}); err == nil {
			res.Data = BabyFrom(b)
		}
	}
	return res, err
}

func (s *Service) apply(ctx context.Context, q *store.Queries, actor auth.Claims, seq int64, m Mutation) (outcome, error) {
	switch m.Op {
	case "upsert", "delete", "restore":
	default:
		return rejected("validation_failed", map[string]string{"op": "invalid"}), nil
	}
	switch m.Entity {
	case "event":
		return s.applyEvent(ctx, q, actor, seq, m)
	case "baby":
		return s.applyBaby(ctx, q, actor, seq, m)
	default:
		return rejected("validation_failed", map[string]string{"entity": "invalid"}), nil
	}
}

func (s *Service) applyEvent(ctx context.Context, q *store.Queries, actor auth.Claims, seq int64, m Mutation) (outcome, error) {
	key := store.GetEventParams{ID: m.EntityID, FamilyID: actor.FamilyID}
	cur, err := q.GetEventForUpdate(ctx, store.GetEventForUpdateParams(key))
	exists := err == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return outcome{}, err
	}
	next := cur

	switch {
	case !exists && m.Op != "upsert":
		return rejected("not_found", nil), nil

	case !exists:
		next = store.Event{ID: m.EntityID, FamilyID: actor.FamilyID}
		if errs := mergeErrs(patchEvent(&next, m.Fields, true), validateEvent(&next)); len(errs) > 0 {
			return rejected("validation_failed", errs), nil
		}
		baby, err := q.GetBaby(ctx, store.GetBabyParams{ID: next.BabyID, FamilyID: actor.FamilyID})
		if errors.Is(err, pgx.ErrNoRows) {
			// Also covers a baby of another family: indistinguishable on purpose.
			return rejected("not_found", map[string]string{"baby_id": "not_found"}), nil
		}
		if err != nil {
			return outcome{}, err
		}
		if baby.DeletedAt != nil {
			return rejected("entity_deleted", map[string]string{"baby_id": "deleted"}), nil
		}
		rows, err := q.InsertEvent(ctx, store.InsertEventParams{
			ID: next.ID, FamilyID: next.FamilyID, BabyID: next.BabyID, Type: next.Type,
			StartedAt: next.StartedAt, EndedAt: next.EndedAt, Timezone: next.Timezone,
			AmountMl: next.AmountMl, MilkKind: next.MilkKind, DiaperKind: next.DiaperKind, SleepKind: next.SleepKind,
			WeightG: next.WeightG, HeightMm: next.HeightMm, HeadMm: next.HeadMm, Notes: next.Notes,
			Seq: seq, CreatedByUserID: actor.UserID, CreatedByDeviceID: actor.DeviceID,
		})
		if err != nil {
			return outcome{}, err
		}
		if rows == 0 {
			// The ID exists but is not visible to this family.
			return rejected("forbidden", nil), nil
		}

	default:
		switch m.Op {
		case "delete":
			if cur.DeletedAt != nil {
				return outcome{data: EventFrom(cur)}, nil
			}
			now := s.now()
			next.DeletedAt = &now
		case "restore":
			if cur.DeletedAt == nil {
				return outcome{data: EventFrom(cur)}, nil
			}
			next.DeletedAt = nil
		case "upsert":
			if cur.DeletedAt != nil {
				return rejected("entity_deleted", nil), nil
			}
			if len(m.Fields) == 0 {
				return outcome{data: EventFrom(cur)}, nil
			}
			if errs := mergeErrs(patchEvent(&next, m.Fields, false), validateEvent(&next)); len(errs) > 0 {
				return rejected("validation_failed", errs), nil
			}
		}
		err := q.UpdateEvent(ctx, store.UpdateEventParams{
			ID: next.ID, FamilyID: next.FamilyID,
			StartedAt: next.StartedAt, EndedAt: next.EndedAt, Timezone: next.Timezone,
			AmountMl: next.AmountMl, MilkKind: next.MilkKind, DiaperKind: next.DiaperKind, SleepKind: next.SleepKind,
			WeightG: next.WeightG, HeightMm: next.HeightMm, HeadMm: next.HeadMm, Notes: next.Notes,
			DeletedAt: next.DeletedAt, Seq: seq, UpdatedByUserID: actor.UserID, UpdatedByDeviceID: actor.DeviceID,
		})
		if err != nil {
			return outcome{}, err
		}
	}

	saved, err := q.GetEvent(ctx, key)
	if err != nil {
		return outcome{}, err
	}
	return outcome{wrote: true, merged: exists && m.BaseVersion < cur.Version, data: EventFrom(saved)}, nil
}

func (s *Service) applyBaby(ctx context.Context, q *store.Queries, actor auth.Claims, seq int64, m Mutation) (outcome, error) {
	key := store.GetBabyParams{ID: m.EntityID, FamilyID: actor.FamilyID}
	cur, err := q.GetBabyForUpdate(ctx, store.GetBabyForUpdateParams(key))
	exists := err == nil
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return outcome{}, err
	}
	next := cur

	switch {
	case !exists && m.Op != "upsert":
		return rejected("not_found", nil), nil

	case !exists:
		next = store.Baby{ID: m.EntityID, FamilyID: actor.FamilyID}
		if errs := mergeErrs(patchBaby(&next, m.Fields), validateBaby(&next)); len(errs) > 0 {
			return rejected("validation_failed", errs), nil
		}
		rows, err := q.InsertBaby(ctx, store.InsertBabyParams{
			ID: next.ID, FamilyID: next.FamilyID, Name: next.Name, DateOfBirth: next.DateOfBirth,
			Sex: next.Sex, Notes: next.Notes, Seq: seq,
			CreatedByUserID: actor.UserID, CreatedByDeviceID: actor.DeviceID,
		})
		if err != nil {
			return outcome{}, err
		}
		if rows == 0 {
			return rejected("forbidden", nil), nil
		}

	default:
		switch m.Op {
		case "delete":
			if cur.DeletedAt != nil {
				return outcome{data: BabyFrom(cur)}, nil
			}
			now := s.now()
			next.DeletedAt = &now
		case "restore":
			if cur.DeletedAt == nil {
				return outcome{data: BabyFrom(cur)}, nil
			}
			next.DeletedAt = nil
		case "upsert":
			if cur.DeletedAt != nil {
				return rejected("entity_deleted", nil), nil
			}
			if len(m.Fields) == 0 {
				return outcome{data: BabyFrom(cur)}, nil
			}
			if errs := mergeErrs(patchBaby(&next, m.Fields), validateBaby(&next)); len(errs) > 0 {
				return rejected("validation_failed", errs), nil
			}
		}
		err := q.UpdateBaby(ctx, store.UpdateBabyParams{
			ID: next.ID, FamilyID: next.FamilyID, Name: next.Name, DateOfBirth: next.DateOfBirth,
			Sex: next.Sex, Notes: next.Notes, DeletedAt: next.DeletedAt, Seq: seq,
			UpdatedByUserID: actor.UserID, UpdatedByDeviceID: actor.DeviceID,
		})
		if err != nil {
			return outcome{}, err
		}
	}

	saved, err := q.GetBaby(ctx, key)
	if err != nil {
		return outcome{}, err
	}
	return outcome{wrote: true, merged: exists && m.BaseVersion < cur.Version, data: BabyFrom(saved)}, nil
}

// mergeErrs reports patch errors first: a field that failed to parse should not also be
// reported as missing.
func mergeErrs(patch, validation map[string]string) map[string]string {
	for k, v := range patch {
		validation[k] = v
	}
	return validation
}

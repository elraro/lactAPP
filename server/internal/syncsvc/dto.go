// Package syncsvc implements the synchronization protocol described in docs/SYNC.md:
// the change feed (pull) and idempotent mutation application (push).
package syncsvc

import (
	"time"

	"github.com/google/uuid"

	"babytracker/internal/store"
)

const dateLayout = "2006-01-02"

// FamilyDTO, BabyDTO and EventDTO are the wire representations from docs/API.md §4.

type FamilyDTO struct {
	ID        uuid.UUID `json:"id"`
	Name      string    `json:"name"`
	Timezone  string    `json:"timezone"`
	AIEnabled bool      `json:"ai_enabled"`
	Version   int32     `json:"version"`
	Seq       int64     `json:"seq"`
	UpdatedAt time.Time `json:"updated_at"`
}

func FamilyFrom(f store.Family) FamilyDTO {
	return FamilyDTO{
		ID: f.ID, Name: f.Name, Timezone: f.Timezone, AIEnabled: f.AiEnabled,
		Version: f.Version, Seq: f.Seq, UpdatedAt: f.UpdatedAt.UTC(),
	}
}

type BabyDTO struct {
	ID          uuid.UUID  `json:"id"`
	Name        string     `json:"name"`
	DateOfBirth string     `json:"date_of_birth"`
	Sex         *string    `json:"sex"`
	Notes       *string    `json:"notes"`
	Version     int32      `json:"version"`
	Seq         int64      `json:"seq"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
	DeletedAt   *time.Time `json:"deleted_at"`
}

func BabyFrom(b store.Baby) BabyDTO {
	return BabyDTO{
		ID: b.ID, Name: b.Name, DateOfBirth: b.DateOfBirth.Format(dateLayout), Sex: b.Sex, Notes: b.Notes,
		Version: b.Version, Seq: b.Seq,
		CreatedAt: b.CreatedAt.UTC(), UpdatedAt: b.UpdatedAt.UTC(), DeletedAt: utcPtr(b.DeletedAt),
	}
}

type EventDTO struct {
	ID                uuid.UUID  `json:"id"`
	BabyID            uuid.UUID  `json:"baby_id"`
	Type              string     `json:"type"`
	StartedAt         time.Time  `json:"started_at"`
	EndedAt           *time.Time `json:"ended_at"`
	Timezone          string     `json:"timezone"`
	AmountMl          *int32     `json:"amount_ml"`
	MilkKind          *string    `json:"milk_kind"`
	DiaperKind        *string    `json:"diaper_kind"`
	SleepKind         *string    `json:"sleep_kind"`
	WeightG           *int32     `json:"weight_g"`
	HeightMm          *int32     `json:"height_mm"`
	HeadMm            *int32     `json:"head_mm"`
	Notes             *string    `json:"notes"`
	Version           int32      `json:"version"`
	Seq               int64      `json:"seq"`
	CreatedAt         time.Time  `json:"created_at"`
	UpdatedAt         time.Time  `json:"updated_at"`
	DeletedAt         *time.Time `json:"deleted_at"`
	CreatedByUserID   uuid.UUID  `json:"created_by_user_id"`
	CreatedByDeviceID uuid.UUID  `json:"created_by_device_id"`
	UpdatedByUserID   uuid.UUID  `json:"updated_by_user_id"`
	UpdatedByDeviceID uuid.UUID  `json:"updated_by_device_id"`
}

func EventFrom(e store.Event) EventDTO {
	return EventDTO{
		ID: e.ID, BabyID: e.BabyID, Type: e.Type,
		StartedAt: e.StartedAt.UTC(), EndedAt: utcPtr(e.EndedAt), Timezone: e.Timezone,
		AmountMl: e.AmountMl, MilkKind: e.MilkKind, DiaperKind: e.DiaperKind, SleepKind: e.SleepKind,
		WeightG: e.WeightG, HeightMm: e.HeightMm, HeadMm: e.HeadMm, Notes: e.Notes,
		Version: e.Version, Seq: e.Seq,
		CreatedAt: e.CreatedAt.UTC(), UpdatedAt: e.UpdatedAt.UTC(), DeletedAt: utcPtr(e.DeletedAt),
		CreatedByUserID: e.CreatedByUserID, CreatedByDeviceID: e.CreatedByDeviceID,
		UpdatedByUserID: e.UpdatedByUserID, UpdatedByDeviceID: e.UpdatedByDeviceID,
	}
}

func utcPtr(t *time.Time) *time.Time {
	if t == nil {
		return nil
	}
	u := t.UTC()
	return &u
}

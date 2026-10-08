package syncsvc

import (
	"bytes"
	"encoding/json"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"

	"babytracker/internal/auth"
	"babytracker/internal/store"
)

const maxNotesLen = 4000

// decode parses a JSON value into T. A JSON null yields (nil, true).
func decode[T any](raw json.RawMessage) (*T, bool) {
	if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return nil, true
	}
	var v T
	if err := json.Unmarshal(raw, &v); err != nil {
		return nil, false
	}
	return &v, true
}

// setOptional writes a nullable field.
func setOptional[T any](dst **T, raw json.RawMessage, key string, errs map[string]string) {
	v, ok := decode[T](raw)
	if !ok {
		errs[key] = "invalid"
		return
	}
	*dst = v
}

// setRequired writes a field that may not be null.
func setRequired[T any](dst *T, raw json.RawMessage, key string, errs map[string]string) {
	v, ok := decode[T](raw)
	switch {
	case !ok:
		errs[key] = "invalid"
	case v == nil:
		errs[key] = "required"
	default:
		*dst = *v
	}
}

// setCreateOnly writes a field on create and rejects any attempt to change it later.
func setCreateOnly[T comparable](dst *T, raw json.RawMessage, key string, creating bool, errs map[string]string) {
	v := *dst
	setRequired(&v, raw, key, errs)
	if errs[key] != "" {
		return
	}
	if creating {
		*dst = v
	} else if v != *dst {
		errs[key] = "immutable"
	}
}

// patchEvent applies the client's changed fields to ev. Only writable fields are accepted.
func patchEvent(ev *store.Event, fields map[string]json.RawMessage, creating bool) map[string]string {
	errs := map[string]string{}
	for key, raw := range fields {
		switch key {
		case "baby_id":
			setCreateOnly(&ev.BabyID, raw, key, creating, errs)
		case "type":
			setCreateOnly(&ev.Type, raw, key, creating, errs)
		case "started_at":
			setRequired(&ev.StartedAt, raw, key, errs)
		case "ended_at":
			setOptional(&ev.EndedAt, raw, key, errs)
		case "timezone":
			setRequired(&ev.Timezone, raw, key, errs)
		case "amount_ml":
			setOptional(&ev.AmountMl, raw, key, errs)
		case "milk_kind":
			setOptional(&ev.MilkKind, raw, key, errs)
		case "diaper_kind":
			setOptional(&ev.DiaperKind, raw, key, errs)
		case "sleep_kind":
			setOptional(&ev.SleepKind, raw, key, errs)
		case "weight_g":
			setOptional(&ev.WeightG, raw, key, errs)
		case "height_mm":
			setOptional(&ev.HeightMm, raw, key, errs)
		case "head_mm":
			setOptional(&ev.HeadMm, raw, key, errs)
		case "notes":
			setOptional(&ev.Notes, raw, key, errs)
		default:
			errs[key] = "not_writable"
		}
	}
	return errs
}

// validateEvent checks the resulting row. It mirrors the CHECK constraints in the
// migration so that a violation is reported per field instead of as a database error.
func validateEvent(ev *store.Event) map[string]string {
	errs := map[string]string{}
	ev.Notes = normalizeNotes(ev.Notes, errs)

	if ev.BabyID == uuid.Nil {
		errs["baby_id"] = "required"
	}
	if ev.StartedAt.IsZero() {
		errs["started_at"] = "required"
	} else if !plausibleInstant(ev.StartedAt) {
		errs["started_at"] = "out_of_range"
	}
	if ev.EndedAt != nil {
		if !plausibleInstant(*ev.EndedAt) {
			errs["ended_at"] = "out_of_range"
		} else if ev.EndedAt.Before(ev.StartedAt) {
			errs["ended_at"] = "before_start"
		}
	}
	if ev.Timezone == "" {
		errs["timezone"] = "required"
	} else if !auth.ValidTimezone(ev.Timezone) {
		errs["timezone"] = "invalid"
	}

	// Which type-specific columns each type may carry.
	allowed := map[string]bool{}
	switch ev.Type {
	case "bottle":
		allowed["amount_ml"], allowed["milk_kind"], allowed["ended_at"] = true, true, true
		if ev.AmountMl == nil {
			errs["amount_ml"] = "required"
		}
	case "diaper":
		allowed["diaper_kind"] = true
		if ev.DiaperKind == nil {
			errs["diaper_kind"] = "required"
		}
	case "sleep":
		allowed["sleep_kind"], allowed["ended_at"] = true, true
		if ev.SleepKind == nil {
			errs["sleep_kind"] = "required"
		}
	case "measurement":
		allowed["weight_g"], allowed["height_mm"], allowed["head_mm"] = true, true, true
		if ev.WeightG == nil && ev.HeightMm == nil && ev.HeadMm == nil {
			errs["weight_g"] = "required"
		}
	case "note":
		if ev.Notes == nil {
			errs["notes"] = "required"
		}
	case "":
		errs["type"] = "required"
		return errs
	default:
		errs["type"] = "invalid"
		return errs
	}

	present := map[string]bool{
		"ended_at": ev.EndedAt != nil, "amount_ml": ev.AmountMl != nil, "milk_kind": ev.MilkKind != nil,
		"diaper_kind": ev.DiaperKind != nil, "sleep_kind": ev.SleepKind != nil,
		"weight_g": ev.WeightG != nil, "height_mm": ev.HeightMm != nil, "head_mm": ev.HeadMm != nil,
	}
	for key, set := range present {
		if set && !allowed[key] {
			errs[key] = "not_allowed"
		}
	}

	checkRange(errs, "amount_ml", ev.AmountMl, 1, 1000)
	checkRange(errs, "weight_g", ev.WeightG, 200, 60000)
	checkRange(errs, "height_mm", ev.HeightMm, 200, 2000)
	checkRange(errs, "head_mm", ev.HeadMm, 100, 800)
	checkEnum(errs, "milk_kind", ev.MilkKind, "formula", "breast_milk", "mixed")
	checkEnum(errs, "diaper_kind", ev.DiaperKind, "wet", "dirty", "both")
	checkEnum(errs, "sleep_kind", ev.SleepKind, "nap", "night")
	return errs
}

// patchBaby applies the client's changed fields to b.
func patchBaby(b *store.Baby, fields map[string]json.RawMessage) map[string]string {
	errs := map[string]string{}
	for key, raw := range fields {
		switch key {
		case "name":
			setRequired(&b.Name, raw, key, errs)
		case "date_of_birth":
			var s string
			setRequired(&s, raw, key, errs)
			if errs[key] == "" {
				d, err := time.Parse(dateLayout, s)
				if err != nil {
					errs[key] = "invalid"
				} else {
					b.DateOfBirth = d
				}
			}
		case "sex":
			setOptional(&b.Sex, raw, key, errs)
		case "notes":
			setOptional(&b.Notes, raw, key, errs)
		default:
			errs[key] = "not_writable"
		}
	}
	return errs
}

func validateBaby(b *store.Baby) map[string]string {
	errs := map[string]string{}
	b.Name = strings.TrimSpace(b.Name)
	b.Notes = normalizeNotes(b.Notes, errs)
	switch n := utf8.RuneCountInString(b.Name); {
	case n == 0:
		errs["name"] = "required"
	case n > 100:
		errs["name"] = "too_long"
	}
	if b.DateOfBirth.IsZero() {
		errs["date_of_birth"] = "required"
	} else if y := b.DateOfBirth.Year(); y < 1900 || y > 2200 {
		errs["date_of_birth"] = "out_of_range"
	}
	checkEnum(errs, "sex", b.Sex, "female", "male")
	return errs
}

// normalizeNotes turns blank notes into NULL and checks the length limit.
func normalizeNotes(notes *string, errs map[string]string) *string {
	if notes == nil || strings.TrimSpace(*notes) == "" {
		return nil
	}
	if utf8.RuneCountInString(*notes) > maxNotesLen {
		errs["notes"] = "too_long"
	}
	return notes
}

// plausibleInstant rejects garbage without second-guessing a device whose clock is off:
// a wrong time is recoverable by editing the event, a rejected event is lost.
func plausibleInstant(t time.Time) bool {
	y := t.UTC().Year()
	return y >= 2000 && y <= 2200
}

func checkRange(errs map[string]string, key string, v *int32, lo, hi int32) {
	if v != nil && (*v < lo || *v > hi) && errs[key] == "" {
		errs[key] = "out_of_range"
	}
}

func checkEnum(errs map[string]string, key string, v *string, allowed ...string) {
	if v == nil || errs[key] != "" {
		return
	}
	for _, a := range allowed {
		if *v == a {
			return
		}
	}
	errs[key] = "invalid"
}

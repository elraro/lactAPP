package syncsvc

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"

	"github.com/google/uuid"

	"babytracker/internal/store"
)

func TestValidateEvent(t *testing.T) {
	base := map[string]any{
		"baby_id": uuid.NewString(), "started_at": "2026-10-08T14:32:00Z", "timezone": "Europe/Madrid",
	}
	with := func(extra map[string]any) map[string]any {
		m := map[string]any{}
		for k, v := range base {
			m[k] = v
		}
		for k, v := range extra {
			if v == "<omit>" {
				delete(m, k)
			} else {
				m[k] = v
			}
		}
		return m
	}
	tests := []struct {
		name   string
		fields map[string]any
		want   map[string]string
	}{
		{"bottle", with(map[string]any{"type": "bottle", "amount_ml": 120}), nil},
		{"bottle with kind and end", with(map[string]any{"type": "bottle", "amount_ml": 1000, "milk_kind": "formula", "ended_at": "2026-10-08T14:50:00Z"}), nil},
		{"bottle without amount", with(map[string]any{"type": "bottle"}), map[string]string{"amount_ml": "required"}},
		{"bottle zero", with(map[string]any{"type": "bottle", "amount_ml": 0}), map[string]string{"amount_ml": "out_of_range"}},
		{"bottle fractional", with(map[string]any{"type": "bottle", "amount_ml": 120.5}), map[string]string{"amount_ml": "invalid"}},
		{"bottle with diaper column", with(map[string]any{"type": "bottle", "amount_ml": 90, "diaper_kind": "wet"}), map[string]string{"diaper_kind": "not_allowed"}},
		{"diaper", with(map[string]any{"type": "diaper", "diaper_kind": "both"}), nil},
		{"diaper bad kind", with(map[string]any{"type": "diaper", "diaper_kind": "soggy"}), map[string]string{"diaper_kind": "invalid"}},
		{"diaper with end", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "ended_at": "2026-10-08T15:00:00Z"}), map[string]string{"ended_at": "not_allowed"}},
		{"open sleep", with(map[string]any{"type": "sleep", "sleep_kind": "nap"}), nil},
		{"sleep across midnight", with(map[string]any{"type": "sleep", "sleep_kind": "night", "started_at": "2026-10-08T21:30:00Z", "ended_at": "2026-10-09T05:10:00Z"}), nil},
		{"sleep ends before start", with(map[string]any{"type": "sleep", "sleep_kind": "nap", "ended_at": "2026-10-08T14:00:00Z"}), map[string]string{"ended_at": "before_start"}},
		{"measurement", with(map[string]any{"type": "measurement", "weight_g": 5200}), nil},
		{"measurement empty", with(map[string]any{"type": "measurement"}), map[string]string{"weight_g": "required"}},
		{"measurement absurd", with(map[string]any{"type": "measurement", "height_mm": 5}), map[string]string{"height_mm": "out_of_range"}},
		{"note", with(map[string]any{"type": "note", "notes": "first smile"}), nil},
		{"blank note", with(map[string]any{"type": "note", "notes": "   "}), map[string]string{"notes": "required"}},
		{"notes too long", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "notes": strings.Repeat("ñ", 4001)}), map[string]string{"notes": "too_long"}},
		{"unknown type", with(map[string]any{"type": "tantrum"}), map[string]string{"type": "invalid"}},
		{"missing type", with(nil), map[string]string{"type": "required"}},
		{"bad timezone", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "timezone": "Mars/Olympus"}), map[string]string{"timezone": "invalid"}},
		{"Local is not a zone", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "timezone": "Local"}), map[string]string{"timezone": "invalid"}},
		{"missing start", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "started_at": "<omit>"}), map[string]string{"started_at": "required"}},
		{"null start", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "started_at": nil}), map[string]string{"started_at": "required"}},
		{"garbage start", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "started_at": "yesterday"}), map[string]string{"started_at": "invalid"}},
		{"server-owned field", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "family_id": uuid.NewString()}), map[string]string{"family_id": "not_writable"}},
		{"version is server-owned", with(map[string]any{"type": "diaper", "diaper_kind": "wet", "version": 9}), map[string]string{"version": "not_writable"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var ev store.Event
			got := mergeErrs(patchEvent(&ev, rawFields(t, tc.fields), true), validateEvent(&ev))
			if len(got) == 0 {
				got = nil
			}
			if !reflect.DeepEqual(got, tc.want) {
				t.Errorf("errors = %v, want %v", got, tc.want)
			}
		})
	}
}

func TestPatchEventImmutableFields(t *testing.T) {
	baby := uuid.New()
	ev := store.Event{BabyID: baby, Type: "bottle"}
	errs := patchEvent(&ev, rawFields(t, map[string]any{"baby_id": uuid.NewString(), "type": "sleep"}), false)
	want := map[string]string{"baby_id": "immutable", "type": "immutable"}
	if !reflect.DeepEqual(errs, want) {
		t.Errorf("errors = %v, want %v", errs, want)
	}
	// Re-sending the unchanged values is harmless.
	if errs := patchEvent(&ev, rawFields(t, map[string]any{"baby_id": baby.String(), "type": "bottle"}), false); len(errs) != 0 {
		t.Errorf("unexpected errors %v", errs)
	}
}

func TestValidateBaby(t *testing.T) {
	tests := []struct {
		name   string
		fields map[string]any
		want   map[string]string
	}{
		{"ok", map[string]any{"name": " Vera ", "date_of_birth": "2026-07-01"}, nil},
		{"with sex", map[string]any{"name": "Vera", "date_of_birth": "2026-07-01", "sex": "female"}, nil},
		{"no name", map[string]any{"date_of_birth": "2026-07-01"}, map[string]string{"name": "required"}},
		{"bad date", map[string]any{"name": "Vera", "date_of_birth": "01/07/2026"}, map[string]string{"date_of_birth": "invalid"}},
		{"impossible date", map[string]any{"name": "Vera", "date_of_birth": "2026-02-30"}, map[string]string{"date_of_birth": "invalid"}},
		{"bad sex", map[string]any{"name": "Vera", "date_of_birth": "2026-07-01", "sex": "x"}, map[string]string{"sex": "invalid"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var b store.Baby
			got := mergeErrs(patchBaby(&b, rawFields(t, tc.fields)), validateBaby(&b))
			if len(got) == 0 {
				got = nil
			}
			if !reflect.DeepEqual(got, tc.want) {
				t.Errorf("errors = %v, want %v", got, tc.want)
			}
			if tc.want == nil && b.Name != "Vera" {
				t.Errorf("name not trimmed: %q", b.Name)
			}
		})
	}
}

func TestCursorRoundTrip(t *testing.T) {
	epoch := uuid.New()
	gotEpoch, gotSeq, err := decodeCursor(encodeCursor(epoch, 812))
	if err != nil || gotEpoch != epoch || gotSeq != 812 {
		t.Fatalf("round trip = %v, %d, %v", gotEpoch, gotSeq, err)
	}
	for _, bad := range []string{"!!!", "abc", "MTp4Ong", "Mjo6"} {
		if _, _, err := decodeCursor(bad); err == nil {
			t.Errorf("cursor %q accepted", bad)
		}
	}
}

func rawFields(t *testing.T, in map[string]any) map[string]json.RawMessage {
	t.Helper()
	out := map[string]json.RawMessage{}
	for k, v := range in {
		raw, err := json.Marshal(v)
		if err != nil {
			t.Fatal(err)
		}
		out[k] = raw
	}
	return out
}

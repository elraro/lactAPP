package predict

import (
	"bytes"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"babytracker/internal/stats"
)

func at(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func sleep(id int, start, end time.Time, kind string) stats.Event {
	e := stats.Event{ID: fmt.Sprintf("s%03d", id), Type: "sleep", StartedAt: start, SleepKind: &kind, Timezone: "Europe/Madrid"}
	if !end.IsZero() {
		e.EndedAt = &end
	}
	return e
}

// week builds six regular days in Madrid (UTC+2): the night ends at 07:00 local, the
// morning wake window lasts morning[i] minutes, the only nap lasts an hour, and bedtime
// is 19:00, starting on 2 October. The data ends with the wake-up of the following morning
// (07:00 on 8 October for six days).
func week(morning []int) []stats.Event {
	var events []stats.Event
	id := 0
	for i, window := range morning {
		wake := at("2026-10-02T05:00:00Z").AddDate(0, 0, i)
		bedtimeBefore := wake.Add(-12 * time.Hour)
		id++
		events = append(events, sleep(id, bedtimeBefore, wake, "night"))
		napStart := wake.Add(time.Duration(window) * time.Minute)
		id++
		events = append(events, sleep(id, napStart, napStart.Add(time.Hour), "nap"))
	}
	lastWake := at("2026-10-02T05:00:00Z").AddDate(0, 0, len(morning))
	return append(events, sleep(id+1, lastWake.Add(-12*time.Hour), lastWake, "night"))
}

// The numbers in these tests are worked out by hand in the comments, so that the golden
// fixtures are not merely "whatever the code produced".

func TestMorningEstimateByHand(t *testing.T) {
	events := week([]int{80, 85, 90, 95, 100, 90})
	out, err := NextSleep(Input{Timezone: "Europe/Madrid", Now: at("2026-10-08T05:20:00Z"), Events: events})
	if err != nil {
		t.Fatal(err)
	}
	// Morning windows, oldest first: 80, 85, 90, 95, 100, 90 min; the window of j days ago
	// ended a little under j days ago, so its age is j-1 whole days: weights 3,3,4,4,4,4.
	// Sorted with weights: 80(3) 85(3) 90(4) 90(4) 95(4) 100(4), total 22.
	// 25 % needs cumulative ≥ 5.5 → 85; 50 % ≥ 11 → 90; 75 % ≥ 16.5 → 95.
	want := Basis{
		WakeWindowOrdinal: 1, SampleSize: 6, SampleRule: RuleSameOrdinal, AwakeSince: at("2026-10-08T05:00:00Z"),
		P25S: 85 * 60, P50S: 90 * 60, P75S: 95 * 60, LookbackDays: 30,
	}
	if out.Status != StatusOK || *out.Basis != want {
		t.Fatalf("status %s, basis = %+v\nwant %+v", out.Status, out.Basis, want)
	}
	// The 10-minute band is widened to 20 minutes around its centre, 08:30 local.
	if !out.WindowStart.Equal(at("2026-10-08T06:20:00Z")) || !out.WindowEnd.Equal(at("2026-10-08T06:40:00Z")) || !out.Point.Equal(at("2026-10-08T06:30:00Z")) {
		t.Errorf("window = %s – %s, point %s", out.WindowStart, out.WindowEnd, out.Point)
	}
	// Six samples with a spread of 10/90 = 11 %: enough for moderate, not for high (needs ten).
	if *out.Kind != "nap" || *out.Confidence != "moderate" || *out.Overdue {
		t.Errorf("kind %s, confidence %s, overdue %v", *out.Kind, *out.Confidence, *out.Overdue)
	}
}

func TestBedtimeEstimateByHand(t *testing.T) {
	events := week([]int{80, 85, 90, 95, 100, 90})
	// On the seventh day the nap ran from 08:30 to 09:30 local; it is now noon.
	events = append(events, sleep(99, at("2026-10-08T06:30:00Z"), at("2026-10-08T07:30:00Z"), "nap"))
	out, err := NextSleep(Input{Timezone: "Europe/Madrid", Now: at("2026-10-08T10:00:00Z"), Events: events})
	if err != nil {
		t.Fatal(err)
	}
	// Second windows ran from the end of the nap to 19:00: 580, 575, 570, 565, 560, 570 min.
	// Sorted with weights: 560(4) 565(4) 570(4) 570(4) 575(3) 580(3), total 22.
	// 25 % → 565, 50 % → 570, 75 % → 575.
	if out.Basis.WakeWindowOrdinal != 2 || out.Basis.P25S != 565*60 || out.Basis.P50S != 570*60 || out.Basis.P75S != 575*60 {
		t.Fatalf("basis = %+v", out.Basis)
	}
	// 09:30 + 9 h 30 = 19:00 local, the usual bedtime: the next sleep is the night.
	if !out.Point.Equal(at("2026-10-08T17:00:00Z")) || *out.Kind != "night" {
		t.Errorf("point %s, kind %s", out.Point, *out.Kind)
	}
	if !out.WindowStart.Equal(at("2026-10-08T16:50:00Z")) || !out.WindowEnd.Equal(at("2026-10-08T17:10:00Z")) {
		t.Errorf("window = %s – %s", out.WindowStart, out.WindowEnd)
	}

	// Four hours past the window with the baby still awake: flagged as overdue.
	late, _ := NextSleep(Input{Timezone: "Europe/Madrid", Now: at("2026-10-08T21:10:00Z"), Events: events})
	if late.Status != StatusOK || !*late.Overdue {
		t.Errorf("late estimate = %+v", late)
	}
}

func TestHighConfidenceNeedsTenConsistentSamples(t *testing.T) {
	// Twelve mornings between 87 and 93 minutes; the baby woke up twenty minutes ago.
	out, err := NextSleep(Input{
		Timezone: "Europe/Madrid", Now: at("2026-10-14T05:20:00Z"),
		Events: week([]int{90, 92, 88, 90, 91, 89, 90, 93, 87, 90, 90, 91}),
	})
	if err != nil {
		t.Fatal(err)
	}
	if out.Status != StatusOK || *out.Confidence != "high" || out.Basis.SampleSize != 12 {
		t.Errorf("estimate = %+v, basis %+v", out, out.Basis)
	}
	// The same consistency with only six mornings is "moderate" (TestMorningEstimateByHand).
}

func TestStatuses(t *testing.T) {
	events := week([]int{80, 85, 90, 95, 100, 90})
	tests := []struct {
		name   string
		now    string
		events []stats.Event
		want   string
	}{
		{"nothing recorded", "2026-10-08T05:20:00Z", nil, StatusInsufficient},
		{"asleep", "2026-10-08T04:00:00Z", events, StatusAsleep},
		// The estimate is made "as of now": a session that ends later is still in progress.
		{"asleep in a session that ended later", "2026-10-07T23:00:00Z", events, StatusAsleep},
		{"awake", "2026-10-08T05:20:00Z", events, StatusOK},
		{"awake for a whole day: stale", "2026-10-09T06:00:00Z", events, StatusInsufficient},
		// Woken at 03:00 local in the middle of the night: daytime windows do not apply.
		{"night waking", "2026-10-08T01:10:00Z", append(week([]int{80, 85, 90, 95, 100, 90})[:12], sleep(50, at("2026-10-07T17:00:00Z"), at("2026-10-08T01:00:00Z"), "night")), StatusNightWaking},
		{"one day of records", "2026-10-03T05:20:00Z", week([]int{90}), StatusInsufficient},
	}
	for _, tc := range tests {
		out, err := NextSleep(Input{Timezone: "Europe/Madrid", Now: at(tc.now), Events: tc.events})
		if err != nil || out.Status != tc.want {
			t.Errorf("%s: status = %q, %v; want %q", tc.name, out.Status, err, tc.want)
		}
		if out.Status != StatusOK && (out.Kind != nil || out.WindowStart != nil || out.Basis != nil) {
			t.Errorf("%s: fields set without an estimate: %+v", tc.name, out)
		}
	}
	if _, err := NextSleep(Input{Timezone: "Mars/Olympus", Now: at("2026-10-08T05:20:00Z")}); err == nil {
		t.Error("invalid timezone accepted")
	}
}

func TestWeightedPercentile(t *testing.T) {
	base := at("2026-10-01T00:00:00Z")
	samples := []sample{{100, 1, base}, {200, 1, base}, {300, 2, base}, {400, 4, base}}
	// Cumulative weights 1, 2, 4, 8 of 8.
	for pct, want := range map[int]int{0: 100, 12: 100, 13: 200, 25: 200, 26: 300, 50: 300, 51: 400, 100: 400} {
		if got := weightedPercentile(samples, pct); got != want {
			t.Errorf("weightedPercentile(%d) = %d, want %d", pct, got, want)
		}
	}
	for age, want := range map[time.Duration]int{0: 4, 95 * time.Hour: 4, 96 * time.Hour: 3, 8 * 24 * time.Hour: 2, 15 * 24 * time.Hour: 1} {
		if got := recencyWeight(age); got != want {
			t.Errorf("recencyWeight(%s) = %d, want %d", age, got, want)
		}
	}
}

type fixture struct {
	Input    Input           `json:"input"`
	Expected json.RawMessage `json:"expected"`
}

// TestFixtures compares the engine with the golden files shared with the Kotlin engine.
// UPDATE_FIXTURES=1 rewrites the expected outputs after a deliberate change of method.
func TestFixtures(t *testing.T) {
	paths, err := filepath.Glob(filepath.Join("..", "..", "..", "testdata", "predict", "*.json"))
	if err != nil || len(paths) == 0 {
		t.Fatalf("no fixtures found: %v", err)
	}
	statuses := map[string]int{}
	for _, path := range paths {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		var f fixture
		if err := json.Unmarshal(raw, &f); err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		out, err := NextSleep(f.Input)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		statuses[out.Status]++
		got, _ := json.Marshal(out)
		if os.Getenv("UPDATE_FIXTURES") == "1" {
			f.Expected = got
			updated, _ := json.MarshalIndent(f, "", " ")
			if err := os.WriteFile(path, append(updated, '\n'), 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		var want, have any
		if err := json.Unmarshal(f.Expected, &want); err != nil || want == nil {
			t.Fatalf("%s has no expected output (run with UPDATE_FIXTURES=1)", path)
		}
		_ = json.Unmarshal(got, &have)
		if !reflect.DeepEqual(want, have) {
			var pretty bytes.Buffer
			_ = json.Indent(&pretty, got, "", " ")
			t.Errorf("%s differs; got:\n%s", path, pretty.String())
		}
	}
	// The fixtures are only useful if they cover every outcome.
	for _, status := range []string{StatusOK, StatusAsleep, StatusInsufficient, StatusNightWaking} {
		if statuses[status] == 0 {
			t.Errorf("no fixture produces status %q", status)
		}
	}
}

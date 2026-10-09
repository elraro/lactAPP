package stats

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"
)

func TestPercentile(t *testing.T) {
	tests := []struct {
		sorted []int
		pct    int
		want   int
	}{
		{[]int{7}, 50, 7},
		{[]int{1, 2, 3, 4}, 50, 3}, // 2.5 rounds half up
		{[]int{1, 2, 3, 4}, 25, 2}, // 1.75
		{[]int{1, 2, 3, 4}, 75, 3}, // 3.25
		{[]int{10, 20, 30, 40, 50}, 25, 20},
		{[]int{10, 20, 30, 40, 50}, 75, 40},
		{[]int{0, 100}, 25, 25},
		{[]int{0, 1}, 50, 1}, // 0.5 rounds up
		{[]int{5400, 5460, 6120, 6900}, 50, 5790},
	}
	for _, tc := range tests {
		if got := percentile(tc.sorted, tc.pct); got != tc.want {
			t.Errorf("percentile(%v, %d) = %d, want %d", tc.sorted, tc.pct, got, tc.want)
		}
	}
}

func TestMeanHalfUp(t *testing.T) {
	for _, tc := range []struct {
		xs   []int
		want int
	}{{[]int{1, 2}, 2}, {[]int{1, 1, 2}, 1}, {[]int{120, 150, 180}, 150}, {[]int{0}, 0}, {[]int{1, 2, 2}, 2}} {
		if got := meanHalfUp(tc.xs); got != tc.want {
			t.Errorf("meanHalfUp(%v) = %d, want %d", tc.xs, got, tc.want)
		}
	}
}

func TestSpearman(t *testing.T) {
	tests := []struct {
		name   string
		xs, ys []int
		want   int
		ok     bool
	}{
		{"perfect", []int{1, 2, 3, 4, 5}, []int{10, 20, 30, 40, 50}, 1000, true},
		{"inverse", []int{1, 2, 3, 4, 5}, []int{9, 7, 5, 3, 1}, -1000, true},
		// Classic textbook example: d² = 0+1+1+1+1 → rho = 1 - 6·4/(5·24) = 0.8
		{"textbook", []int{1, 2, 3, 4, 5}, []int{1, 3, 2, 5, 4}, 800, true},
		// Ties take the average rank: ranks x = 1, 2.5, 2.5, 4.
		{"ties", []int{10, 20, 20, 30}, []int{1, 2, 3, 4}, 949, true},
		{"constant", []int{5, 5, 5}, []int{1, 2, 3}, 0, false},
	}
	for _, tc := range tests {
		got, ok := spearmanX1000(tc.xs, tc.ys)
		if got != tc.want || ok != tc.ok {
			t.Errorf("%s: spearman = %d, %v; want %d, %v", tc.name, got, ok, tc.want, tc.ok)
		}
	}
}

func at(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func sleepEvent(id, start, end, kind string) Event {
	e := Event{ID: id, Type: "sleep", StartedAt: at(start), SleepKind: &kind, Timezone: "Europe/Madrid"}
	if end != "" {
		t := at(end)
		e.EndedAt = &t
	}
	return e
}

func TestNormalizeMergesOverlaps(t *testing.T) {
	sessions := Normalize([]Event{
		sleepEvent("b", "2026-10-08T08:30:00Z", "2026-10-08T09:20:00Z", "nap"),
		sleepEvent("a", "2026-10-08T08:00:00Z", "2026-10-08T09:00:00Z", "nap"),
		sleepEvent("c", "2026-10-08T09:20:00Z", "2026-10-08T09:40:00Z", "nap"), // touches: separate
		sleepEvent("d", "2026-10-08T18:00:00Z", "", "night"),
		sleepEvent("e", "2026-10-08T18:10:00Z", "2026-10-08T18:40:00Z", "nap"), // inside an open session
		{ID: "x", Type: "bottle", StartedAt: at("2026-10-08T08:10:00Z")},
	})
	if len(sessions) != 3 {
		t.Fatalf("got %d sessions, want 3: %+v", len(sessions), sessions)
	}
	if !sessions[0].Start.Equal(at("2026-10-08T08:00:00Z")) || !sessions[0].End.Equal(at("2026-10-08T09:20:00Z")) || sessions[0].Night {
		t.Errorf("merged nap = %+v", sessions[0])
	}
	if sessions[2].End != nil || !sessions[2].Night {
		t.Errorf("open night session = %+v", sessions[2])
	}
}

func TestWakeWindowClassification(t *testing.T) {
	windows := WakeWindowsOf(Normalize([]Event{
		sleepEvent("n1", "2026-10-07T19:00:00Z", "2026-10-08T01:00:00Z", "night"),
		sleepEvent("n2", "2026-10-08T01:20:00Z", "2026-10-08T05:00:00Z", "night"), // 20 min: a night waking
		sleepEvent("a", "2026-10-08T06:30:00Z", "2026-10-08T07:30:00Z", "nap"),
		sleepEvent("b", "2026-10-08T09:30:00Z", "", "nap"), // unfinished
	}))
	if len(windows) != 2 {
		t.Fatalf("got %d windows, want 2: %+v", len(windows), windows)
	}
	if w := windows[0]; w.Duration != 5400 || w.Ordinal != 1 || w.NextNap != 3600 || w.BeforeBedtime {
		t.Errorf("morning window = %+v", w)
	}
	if w := windows[1]; w.Duration != 7200 || w.Ordinal != 2 || w.NextNap != -1 {
		t.Errorf("second window = %+v", w)
	}

	// Data starting with a nap: the position in the day is unknown until a night is seen;
	// a gap longer than 16 h is missing data, not a wake window.
	windows = WakeWindowsOf(Normalize([]Event{
		sleepEvent("a", "2026-10-08T08:00:00Z", "2026-10-08T09:00:00Z", "nap"),
		sleepEvent("b", "2026-10-08T11:00:00Z", "2026-10-08T12:00:00Z", "nap"),
		sleepEvent("n", "2026-10-08T19:00:00Z", "2026-10-09T05:00:00Z", "night"),
		sleepEvent("c", "2026-10-10T08:00:00Z", "2026-10-10T09:00:00Z", "nap"), // 27 h later
		sleepEvent("d", "2026-10-10T11:00:00Z", "2026-10-10T12:00:00Z", "nap"),
	}))
	var got []int
	for _, w := range windows {
		got = append(got, w.Ordinal)
	}
	if want := []int{0, 0, 0}; !reflect.DeepEqual(got, want) {
		t.Errorf("ordinals = %v, want %v (windows %+v)", got, want, windows)
	}
	if !windows[1].BeforeBedtime {
		t.Error("window before night sleep is not flagged")
	}
}

type fixture struct {
	Input    Input           `json:"input"`
	Expected json.RawMessage `json:"expected"`
}

func loadFixture(t *testing.T, name string) (fixture, string) {
	t.Helper()
	path := filepath.Join("..", "..", "..", "testdata", "stats", name+".json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var f fixture
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return f, path
}

// TestBasicDayByHand checks one fixture against numbers worked out on paper, so that the
// golden files are not merely "whatever the code produced".
func TestBasicDayByHand(t *testing.T) {
	f, _ := loadFixture(t, "basic_day")
	out, err := Compute(f.Input)
	if err != nil {
		t.Fatal(err)
	}
	// 8 October in Madrid runs from 22:00Z on the 7th to 22:00Z on the 8th; "now" is 20:00Z.
	if out.Range.CompleteDays != 0 || out.Range.Start != "2026-10-08" {
		t.Errorf("range = %+v", out.Range)
	}
	if out.Feeding.TodayMl != 450 || out.Feeding.TodayFeeds != 3 {
		t.Errorf("feeding today = %d ml in %d feeds", out.Feeding.TodayMl, out.Feeding.TodayFeeds)
	}
	// Two intervals (3 h 30 and 4 h) are below the minimum sample of five.
	if out.Feeding.IntervalS.Median != nil || out.Feeding.IntervalS.SampleSize != 2 {
		t.Errorf("interval = %+v", out.Feeding.IntervalS)
	}
	// 07:30, 11:00 and 15:00 local.
	for hour, want := range map[int]int{7: 150, 11: 120, 15: 180, 12: 0} {
		if got := out.Feeding.ByHour[hour].TotalMl; got != want {
			t.Errorf("ml at %d h = %d, want %d", hour, got, want)
		}
	}
	if out.Diapers.Today != (DiaperCounts{Total: 2, Wet: 1, Both: 1}) {
		t.Errorf("diapers = %+v", out.Diapers.Today)
	}
	// Night: 00:00–07:10 local (25 800 s) plus the open session 21:20–22:00 "now" (2 400 s).
	// Day: naps of 50 min and 70 min.
	want := SleepDay{Date: "2026-10-08", TotalS: 35400, NightS: 28200, DayS: 7200, Naps: 2}
	if out.Sleep.Today != want {
		t.Errorf("sleep today = %+v, want %+v", out.Sleep.Today, want)
	}
	// Wake windows: 2 h 35 after the night, 2 h 55 between naps, 6 h 40 before bedtime.
	if out.WakeWindows.Overall.SampleSize != 3 || out.WakeWindows.Overall.Median != nil {
		t.Errorf("wake windows = %+v", out.WakeWindows.Overall)
	}
	var bins []int
	for _, b := range out.WakeWindows.Histogram {
		if b.Count > 0 {
			bins = append(bins, b.FromS)
		}
	}
	if !reflect.DeepEqual(bins, []int{9000, 9900, 23400}) {
		t.Errorf("histogram bins = %v", bins)
	}
}

func TestDaylightSavingDays(t *testing.T) {
	f, _ := loadFixture(t, "dst_autumn")
	out, err := Compute(f.Input)
	if err != nil {
		t.Fatal(err)
	}
	// 24, 25 (25 hours) and 26 October.
	feeds := []int{out.Feeding.Daily[0].Feeds, out.Feeding.Daily[1].Feeds, out.Feeding.Daily[2].Feeds}
	if !reflect.DeepEqual(feeds, []int{0, 3, 1}) {
		t.Errorf("feeds per day = %v", feeds)
	}
	// The repeated hour: 02:30 happens twice and both feeds land in hour 2.
	if out.Feeding.ByHour[2].Feeds != 2 {
		t.Errorf("feeds at 02 h = %d, want 2", out.Feeding.ByHour[2].Feeds)
	}
	// Night sleep on the 25th: 00:00–06:30 local is 7 h 30 of real time (the hour repeats),
	// plus 20:00–24:00 = 4 h. Nap 75 min.
	if d := out.Sleep.Daily[1]; d.NightS != (7*3600+1800)+4*3600 || d.DayS != 4500 {
		t.Errorf("sleep on the 25-hour day = %+v", d)
	}
	if out.Diapers.Daily[1].Dirty != 1 || out.Diapers.Daily[2].Total != 0 {
		t.Errorf("diaper at 23:59:59 = %+v / %+v", out.Diapers.Daily[1], out.Diapers.Daily[2])
	}

	f, _ = loadFixture(t, "dst_spring")
	if out, err = Compute(f.Input); err != nil {
		t.Fatal(err)
	}
	// 29 March has 23 hours: 00:00 and 23:59 belong to it, the next 00:00 does not.
	if d := out.Feeding.Daily[1]; d.Feeds != 2 || d.TotalMl != 185 {
		t.Errorf("feeds on the 23-hour day = %+v", d)
	}
	// Night sleep on the 29th: 00:00–07:00 local is only 6 h of real time.
	if d := out.Sleep.Daily[1]; d.NightS != 6*3600 {
		t.Errorf("night sleep on the 23-hour day = %d s", d.NightS)
	}
}

func TestInvalidInput(t *testing.T) {
	now := at("2026-10-08T12:00:00Z")
	for name, in := range map[string]Input{
		"timezone": {Timezone: "Mars/Olympus", End: "2026-10-08", Days: 7, Now: now},
		"local":    {Timezone: "Local", End: "2026-10-08", Days: 7, Now: now},
		"date":     {Timezone: "Europe/Madrid", End: "8/10/2026", Days: 7, Now: now},
		"days":     {Timezone: "Europe/Madrid", End: "2026-10-08", Days: 0, Now: now},
	} {
		if _, err := Compute(in); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

// TestFixtures compares the engine with every golden file shared with the Kotlin engine.
// Run with UPDATE_FIXTURES=1 to rewrite the expected outputs after a deliberate change
// to a definition; review the diff, and update docs/STATISTICS.md in the same commit.
func TestFixtures(t *testing.T) {
	paths, err := filepath.Glob(filepath.Join("..", "..", "..", "testdata", "stats", "*.json"))
	if err != nil || len(paths) == 0 {
		t.Fatalf("no fixtures found: %v", err)
	}
	for _, path := range paths {
		name := filepath.Base(path[:len(path)-len(".json")])
		t.Run(name, func(t *testing.T) {
			f, _ := loadFixture(t, name)
			out, err := Compute(f.Input)
			if err != nil {
				t.Fatal(err)
			}
			got, err := json.Marshal(out)
			if err != nil {
				t.Fatal(err)
			}
			if os.Getenv("UPDATE_FIXTURES") == "1" {
				f.Expected = got
				raw, err := json.MarshalIndent(f, "", " ")
				if err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(path, append(raw, '\n'), 0o644); err != nil {
					t.Fatal(err)
				}
				return
			}
			var want, have any
			if err := json.Unmarshal(f.Expected, &want); err != nil || want == nil {
				t.Fatalf("fixture has no expected output (run with UPDATE_FIXTURES=1): %v", err)
			}
			_ = json.Unmarshal(got, &have)
			if !reflect.DeepEqual(want, have) {
				var pretty bytes.Buffer
				_ = json.Indent(&pretty, got, "", " ")
				t.Errorf("output differs from %s; got:\n%s", path, pretty.String())
			}
		})
	}
}

// TestDeterministic guards against map iteration order or other hidden state leaking in.
func TestDeterministic(t *testing.T) {
	f, _ := loadFixture(t, "month_30d")
	first, _ := Compute(f.Input)
	a, _ := json.Marshal(first)
	for i := 0; i < 20; i++ {
		// The order of the input must not matter either.
		events := f.Input.Events
		for l, r := 0, len(events)-1; l < r; l, r = l+1, r-1 {
			events[l], events[r] = events[r], events[l]
		}
		out, _ := Compute(f.Input)
		b, _ := json.Marshal(out)
		if !bytes.Equal(a, b) {
			t.Fatalf("run %d produced a different result", i)
		}
	}
}

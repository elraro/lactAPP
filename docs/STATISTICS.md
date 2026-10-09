# Statistics and prediction

> **Status: APPROVED 2026-10-08.** Implemented in both engines.
> The exact output shape is the Go type `stats.Output` (`server/internal/stats/types.go`),
> mirrored by `StatsOutput` in `android/stats`.

## 1. Rules

1. Statistics are **pure functions**: `f(events, reporting zone, range, now) → result`.
   No database access, no system clock, no randomness inside the engine.
2. No LLM is involved in any number.
3. Every metric reports its **`sample_size`**. Below the metric's minimum the value
   is `null` and the UI says "not enough data" instead of showing a fragile number.
4. The Go engine (`server/internal/stats`, `predict`) and the Kotlin engine
   (`android/stats`) implement this document. **`testdata/stats/*.json`** at the
   repository root holds fixtures — input events plus expected output — and both
   test suites must pass all of them. A change to a definition is a change to this
   document, the fixtures, and both engines in the same commit.
5. Language: results describe **association**, never cause.

## 2. Numeric conventions (needed for cross-language equality)

- Durations are integer **seconds**; volumes integer **ml**. Every result is an integer.
  Per-day rates that need one decimal are reported in tenths (`*_x10`: 55 means 5.5).
- **Mean**: `(2·sum + n) / (2·n)` in integer arithmetic, i.e. rounded half up.
- **Percentile p** of sorted values `x[0..n-1]`: linear interpolation at rank
  `(n − 1)·p/100` ("type 7", as in NumPy/R/Excel `PERCENTILE.INC`), computed with
  integers and rounded half up. Median = p50.
- **Spearman's ρ** is the one floating-point computation: average ranks for ties, then
  Pearson on the ranks with the same operations in the same order in both engines,
  reported as `ρ × 1000` rounded half away from zero.
- Events are processed in order of start time, then ID. The order of the input does not
  matter (tested).
- Deleted events are not part of the input. Open sleep sessions count up to `now` in
  per-day totals and are excluded from every duration distribution.

## 3. Days

- A **day** is `[00:00, next 00:00)` in the reporting zone, computed with zone
  rules (23 h / 25 h on DST days).
- A range of *N days* ending on day *D* is the N calendar days up to and including *D*.
  When *D* is today it is partial; **averages per day exclude the current partial day**.
- Point events belong to the day containing `started_at`.
- Interval events (sleep) contribute to each day the part of the interval that
  falls inside it.
- **Time-of-day of feeds** uses each event's own `timezone` (falling back to the
  reporting zone if it is unknown), so travel does not smear the pattern.
  **Time-of-day of naps** uses the reporting zone, because overlapping sessions recorded
  by two phones are merged before analysis.

## 4. Feeding

| Metric | Definition | Min n |
|---|---|---|
| Total today / per day | Σ `amount_ml` of bottles in the day | — |
| Total over range | Σ per-day totals | — |
| Average daily intake | mean of per-day totals over complete days with ≥ 1 feed | 3 days |
| Feeds per day | count per day; mean over complete days | 3 days |
| Average / median amount per feed | over all bottles in range | 5 |
| Interval between feeds | `started_at[i+1] − started_at[i]` for consecutive feeds; median, p25, p75 | 5 |
| Daily trend | series of per-day totals and counts | — |
| Distribution by time of day | 24 hourly buckets (event-local hour): feed count and ml | 5 |

## 5. Diapers

Per day and over the range: `total`, `wet`, `dirty`, `both` — the kinds as recorded,
each diaper counted once. Mean per day over complete days with at least one diaper
(min 3 days). Daily trend = per-day series.

## 6. Sleep

**Normalization first.** Sessions of a baby are sorted by start; overlapping
sessions are merged into their union (kind = `night` if any part is night). All
metrics below use normalized sessions.

| Metric | Definition | Min n |
|---|---|---|
| Total sleep per day | Σ of session time clipped to the day | — |
| Night / day sleep per day | same, split by `sleep_kind` | — |
| Naps per day | naps whose **start** is in the day; mean over complete days | 3 days |
| Nap duration | mean, median, p25, p75, max over closed naps in range | 5 |
| Average nap start time | arithmetic mean of the start time (minutes after local midnight), per nap ordinal within its day (1st … 5th). Naps are daytime sleep, so no wrap-around at midnight is needed | 5 |

Two consecutive night sessions belong to the **same night** if the gap between
them is < 4 h.

## 7. Wake windows

A **wake window** is the gap between the end of one normalized session and the
start of the next, classified by its neighbours:

| Before → after | Class |
|---|---|
| night → night, gap < 4 h | night waking (not a wake window) |
| night → nap (or night → night with gap ≥ 4 h) | wake window, **ordinal 1** ("morning") |
| nap → nap | wake window, ordinal = naps since morning wake + 1 |
| nap → night | wake window, ordinal as above, flagged `before_bedtime` |

Gaps longer than 16 h are discarded as missing data, and reset the ordinal to
*unknown* (0) until the next night is seen; windows of unknown ordinal count in the
overall figures only. A window belongs to the range if it **ends** (the baby falls
asleep) inside it. Reported: median, p25, p75, min, max, mean, `sample_size` —
overall, per ordinal, and as a histogram with 15-minute bins. Min n = 5 per group.

## 8. Patterns

Shown only when the sample is adequate, always with *n*, always phrased as
association.

| Pattern | Method | Min n |
|---|---|---|
| Wake window ↔ following nap duration | Spearman rank correlation ρ over (window, next nap) pairs, plus median nap duration per wake-window tercile | 12 pairs |
| Nap duration by start time | median duration of naps grouped in 30-min start buckets (event-local) | 4 per bucket |
| Change over time | last 7 days of the range vs. the 7 before (only for ranges of 14 days or more): median wake window, median nap duration (min 5 each per period), mean daily intake, mean daily sleep (min 3 complete days per period). Both values are reported; the UI shows them side by side | see left |

ρ is labelled: |ρ| < 0.2 none · 0.2–0.4 weak · 0.4–0.6 moderate · ≥ 0.6 strong.
The UI text for these labels says "associated with" and includes a fixed note that
this does not show cause.

## 9. Next-sleep prediction (v1)

Implemented by `server/internal/predict` and `android/stats/NextSleep.kt`; fixtures in
`testdata/predict`. Input: sleep events of the last 30 days (plus two), `now`, zone.
All times are truncated to whole seconds and output in UTC.

1. **As of now.** Events that start after `now` are ignored; a session that ends after
   `now` is still in progress. Sessions are normalized as in §6.
2. **Outcomes without an estimate** (`status`):
   - `asleep` — the last session is open;
   - `night_waking` — the last session is night sleep that ended between 20:00 and 05:00
     local time. Daytime wake windows say nothing about how long a night waking lasts;
   - `insufficient_data` — no sessions, awake for more than 16 h (stale records), or
     fewer than 3 comparable windows.
3. `T` = end of the last session; `k` = ordinal of the wake window now in progress
   (§7), obtained by treating `now` as the start of a nap.
4. **Sample** — completed wake windows that ended in the last 30 days:
   `same_ordinal` (ordinal `k`, if at least 5) → else `time_of_day` (window start within
   ± 90 min of `T`'s time of day, at least 5) → else `all` (at least 3).
5. **Recency weights** by whole days since the window ended: 0–3 → 4 · 4–7 → 3 ·
   8–14 → 2 · older → 1.
6. **Band** — weighted percentiles (smallest value whose cumulative weight reaches p % of
   the total; integers only): window = `[T + wP25, T + wP75]`, point = `T + wP50`. A band
   narrower than 20 minutes is replaced by 20 minutes centred on it.
7. **Kind** — `night` if the point's local time is before 06:00, or not earlier than
   45 minutes before the usual bedtime (lower median of the first session of each night
   in the last 14 days; with fewer than 3 bedtimes: from 19:00). Otherwise `nap`.
8. **Confidence** from the sample count *n* and the spread `(wP75 − wP25) / wP50`:

   | Confidence | Condition |
   |---|---|
   | high | n ≥ 10, spread ≤ 25 %, rule `same_ordinal` |
   | moderate | n ≥ 5, spread ≤ 45 % |
   | low | otherwise |

9. `overdue` is true when `now` is past the end of the window.

```json
{ "status": "ok", "kind": "nap",
  "window_start": "2026-10-08T06:20:00Z", "window_end": "2026-10-08T06:40:00Z",
  "point": "2026-10-08T06:30:00Z", "confidence": "moderate", "overdue": false,
  "basis": { "wake_window_ordinal": 1, "sample_size": 6, "sample_rule": "same_ordinal",
             "awake_since": "2026-10-08T05:00:00Z",
             "p25_s": 5100, "p50_s": 5400, "p75_s": 5700, "lookback_days": 30 } }
```

That example is worked out by hand in `predict.TestMorningEstimateByHand`. `basis` is
what the app shows under the estimate and what the AI receives. The method is
intentionally simple and inspectable: it describes what usually happened, and the app
says so ("an estimate from past records, not a schedule").

**Known limits.** It knows nothing about illness, travel, a skipped nap or a short
night; the ordinal resets only at night sleep, so a day with an extra nap shifts the
later ordinals. A backtest over recorded history (share of actual sleep starts inside
the predicted window, per confidence level) is the intended way to tune the thresholds
and is not built yet.

## 10. Growth

Not part of the statistics engine. Measurements are stored as events; the growth view
(a later phase) reads them directly. **No percentiles**: they require the WHO LMS
reference tables and the baby's sex; the schema has room for both.

## 11. Where it runs

| | |
|---|---|
| Server | `GET /api/v1/babies/{id}/stats?days=&end=&tz=` → `stats.Compute`; `GET …/predictions/next-sleep?tz=` → `predict.NextSleep`. Also the input of the AI context. |
| Android | `StatsViewModel` → `Stats.compute` and `TodayViewModel` → `NextSleep.estimate`, over the local database, so both work offline and include unsynced records. The estimate is refreshed every 30 s while Today is visible. Charts are plain Compose `Canvas` bars (no chart library). |
| Fixtures | `testdata/stats/*.json` and `testdata/predict/*.json`. Inputs come from `generate_inputs.py`; expected outputs are written by `UPDATE_FIXTURES=1 go test ./internal/stats/` after the hand-checked Go tests pass. The Kotlin test fails on any difference. |

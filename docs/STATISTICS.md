# Statistics and prediction

> **Status: PROPOSED — awaiting review.** Assumes decision D1 = "both engines".

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

- Durations are integer **seconds**; volumes integer **ml**; weights **g**; lengths **mm**.
- **Mean**: sum / n, rounded half-up to the unit.
- **Percentile p** of sorted values `x[0..n-1]`: linear interpolation at rank
  `h = (n − 1)·p` → `x[⌊h⌋] + (h − ⌊h⌋)·(x[⌈h⌉] − x[⌊h⌋])`, rounded half-up
  (the "type 7" definition used by NumPy/R/Excel `PERCENTILE.INC`). Median = p50.
- **Weighted percentile** (prediction only): sort by value, accumulate integer
  weights, return the first value whose cumulative weight ≥ `p · total`. No
  interpolation, integer arithmetic only.
- Deleted events are ignored. Open sleep sessions are used for "today so far"
  totals (clipped at `now`) and excluded from every duration distribution.

## 3. Days

- A **day** is `[00:00, next 00:00)` in the reporting zone, computed with zone
  rules (23 h / 25 h on DST days).
- A range of *N days* ending on day *D* is the N calendar days up to and including *D*.
  When *D* is today it is partial; **averages per day exclude the current partial day**.
- Point events belong to the day containing `started_at`.
- Interval events (sleep) contribute to each day the part of the interval that
  falls inside it.
- **Time-of-day** metrics use each event's own `timezone`.

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

Per day and over the range: total, wet, dirty, both. For "how many wet/dirty
diapers" summaries a `both` diaper counts once in the total and once in each of
*wet-inclusive* and *dirty-inclusive* counts; all five numbers are returned so the
UI never has to guess. Daily trend = per-day series.

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
| Average nap start time | circular mean of event-local start time-of-day, per nap ordinal (1st, 2nd, …) | 5 |
| Night length | per night: first night-session start → last night-session end of the same night, and sleep time within it | 3 nights |
| Night wakings | count of gaps between consecutive night sessions of the same night | 3 nights |

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

Gaps longer than 16 h are discarded as missing data. Reported: median, p25, p75,
min, max, `sample_size` — overall, per ordinal, and as a histogram with 15-minute
bins. Min n = 5 per group.

## 8. Patterns

Shown only when the sample is adequate, always with *n*, always phrased as
association.

| Pattern | Method | Min n |
|---|---|---|
| Wake window ↔ following nap duration | Spearman rank correlation ρ over (window, next nap) pairs, plus median nap duration per wake-window tercile | 12 pairs |
| Nap duration by start time | median duration of naps grouped in 30-min start buckets (event-local) | 4 per bucket |
| Change over time | median of last 7 days vs. the 7 days before, for wake window, nap duration, daily intake, total sleep; reported as absolute and % difference | 5 per period |

ρ is labelled: |ρ| < 0.2 none · 0.2–0.4 weak · 0.4–0.6 moderate · ≥ 0.6 strong.
The UI text for these labels says "associated with" and includes a fixed note that
this does not show cause.

## 9. Next-sleep prediction (v1)

Input: normalized sessions of the last 30 days, `now`.

1. If a session is open → no prediction (baby is asleep).
2. `T` = end of the last session; `k` = ordinal of the wake window now in progress.
3. **Sample**: completed wake windows with ordinal `k` from the last 30 days.
   If fewer than 5, fall back to all wake windows whose start time-of-day is
   within ± 90 min of `T`'s; if still fewer than 5, all wake windows; fewer than
   3 → **no prediction** (`insufficient_data`).
4. **Recency weights** (integer): age 0–3 days → 4 · 4–7 → 3 · 8–14 → 2 · 15–30 → 1.
5. Window = `[T + wP25, T + wP75]`, point estimate `T + wP50` (weighted percentiles).
   The window is widened to at least 20 minutes.
6. **Kind**: `night` if the point estimate is later than (median night start of
   last 14 days − 45 min), else `nap`.
7. **Confidence** from weighted sample count *n* and relative spread
   `r = (wP75 − wP25) / wP50`:

   | Confidence | Condition |
   |---|---|
   | High | n ≥ 10, r ≤ 0.25, sample from step 3 primary rule |
   | Moderate | n ≥ 5, r ≤ 0.45 |
   | Low | otherwise |

8. If `now` is already past the window's end, the result is flagged `overdue`.

Output:

```json
{ "status": "ok", "kind": "nap",
  "window_start": "2026-10-08T14:10:00Z", "window_end": "2026-10-08T14:35:00Z",
  "point": "2026-10-08T14:22:00Z", "confidence": "moderate", "overdue": false,
  "basis": { "wake_window_ordinal": 3, "sample_size": 11, "sample_rule": "same_ordinal",
             "awake_since": "2026-10-08T12:40:00Z",
             "wP25_s": 5400, "wP50_s": 6120, "wP75_s": 6900, "lookback_days": 30 } }
```

`basis` is exactly what the UI shows under "Why this estimate?" and what the AI
receives. The model is intentionally simple and inspectable.

**Validation.** A backtest tool replays history (for each past wake window, predict
using only earlier data) and reports the share of actual sleep starts that fell
inside the predicted window, per confidence level. Confidence thresholds are only
changed on the basis of that report. The backtest is a developer tool, not a
user-facing feature.

## 10. Growth

Series of weight / height / head measurements over time, and change between
consecutive measurements (absolute and per week). **No percentiles** in v1: they
require the WHO LMS reference tables and the baby's sex; the schema has room for
both, and adding them is a self-contained later change.

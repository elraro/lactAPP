#!/usr/bin/env python3
"""Writes the *inputs* of the next-sleep prediction fixtures (deterministic).

Expected outputs are added by `UPDATE_FIXTURES=1 go test ./internal/predict/` after the
hand-checked Go tests pass; the Kotlin engine must reproduce them exactly. The events
are those of ../stats/month_30d.json, looked at from different moments.
"""
import json
import os
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
EVENTS = json.load(open(os.path.join(HERE, "..", "stats", "month_30d.json")))["input"]["events"]


def parse(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def fmt(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def write(name, now, events, tz="Europe/Madrid"):
    path = os.path.join(HERE, name + ".json")
    new_input = {"timezone": tz, "now": now, "events": events}
    expected = None
    if os.path.exists(path):
        old = json.load(open(path))
        if old.get("input") == new_input:
            expected = old.get("expected")
    with open(path, "w") as f:
        json.dump({"input": new_input, "expected": expected}, f, indent=1)
        f.write("\n")


def main():
    sleeps = sorted((e for e in EVENTS if e["type"] == "sleep"), key=lambda e: e["started_at"])
    day = "2026-10-06"
    naps = [e for e in sleeps if e["sleep_kind"] == "nap" and e["started_at"].startswith(day)]
    nights = [e for e in sleeps if e["sleep_kind"] == "night" and e["ended_at"].startswith(day)]
    morning_wake = parse(nights[-1]["ended_at"])

    write("empty", "2026-10-06T12:00:00Z", [])
    write("after_morning_wake", fmt(morning_wake + timedelta(minutes=10)), EVENTS)
    write("after_first_nap", fmt(parse(naps[0]["ended_at"]) + timedelta(minutes=30)), EVENTS)
    write("after_second_nap_overdue", fmt(parse(naps[1]["ended_at"]) + timedelta(hours=4, minutes=30)), EVENTS)
    write("during_nap", fmt(parse(naps[1]["started_at"]) + timedelta(minutes=15)), EVENTS)
    # A day with three naps: after the last one, the next sleep is the night.
    for back in range(1, 20):
        d = (datetime(2026, 10, 8) - timedelta(days=back)).strftime("%Y-%m-%d")
        three = [e for e in sleeps if e["sleep_kind"] == "nap" and e["started_at"].startswith(d)]
        if len(three) == 3:
            write("after_third_nap", fmt(parse(three[2]["ended_at"]) + timedelta(minutes=15)), EVENTS)
            break
    # The first of two night sessions of the same night ends with a waking.
    night_sessions = [e for e in sleeps if e["sleep_kind"] == "night"]
    for first_part, second_part in zip(night_sessions, night_sessions[1:]):
        gap = parse(second_part["started_at"]) - parse(first_part["ended_at"])
        if gap < timedelta(hours=1) and first_part["ended_at"] > "2026-09-20":
            write("night_waking", fmt(parse(first_part["ended_at"]) + timedelta(minutes=5)), EVENTS)
            break
    # Seen from another zone the instants are the same; only the time-of-day rules move.
    write("after_first_nap_new_york", fmt(parse(naps[0]["ended_at"]) + timedelta(minutes=30)), EVENTS, tz="America/New_York")
    # Early days: only the first three days of records exist.
    first = [e for e in EVENTS if e["started_at"] < "2026-09-05"]
    first_naps = [e for e in first if e["type"] == "sleep" and e["sleep_kind"] == "nap" and e["started_at"].startswith("2026-09-04")]
    write("third_day_of_use", fmt(parse(first_naps[0]["ended_at"]) + timedelta(minutes=25)), first)
    write("stale_records", "2026-09-07T12:00:00Z", first)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Writes the *inputs* of the statistics fixtures. Deterministic: fixed seed, no clock.

The expected outputs are added by the Go engine (`UPDATE_FIXTURES=1 go test ./internal/stats/`)
after its hand-checked unit tests pass; the Kotlin engine must then reproduce them exactly.
Re-running this script keeps existing expected outputs only if the input is unchanged.
"""
import json
import os
import random
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
MADRID = ZoneInfo("Europe/Madrid")
_next_id = 0


def utc(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def event(type_, start, end=None, tz="Europe/Madrid", **fields):
    global _next_id
    _next_id += 1
    base = {"id": f"e{_next_id:05d}", "type": type_, "started_at": start, "ended_at": end, "timezone": tz,
            "amount_ml": None, "diaper_kind": None, "sleep_kind": None}
    base.update(fields)
    return base


def write(name, timezone_, end, days, now, events):
    path = os.path.join(HERE, name + ".json")
    new_input = {"timezone": timezone_, "end": end, "days": days, "now": now, "events": events}
    expected = None
    if os.path.exists(path):
        old = json.load(open(path))
        if old.get("input") == new_input:
            expected = old.get("expected")
    with open(path, "w") as f:
        json.dump({"input": new_input, "expected": expected}, f, indent=1)
        f.write("\n")


def basic_day():
    return [
        event("sleep", "2026-10-07T19:30:00Z", "2026-10-08T05:10:00Z", sleep_kind="night"),
        event("bottle", "2026-10-08T05:30:00Z", amount_ml=150),
        event("diaper", "2026-10-08T06:00:00Z", diaper_kind="wet"),
        event("sleep", "2026-10-08T07:45:00Z", "2026-10-08T08:35:00Z", sleep_kind="nap"),
        event("bottle", "2026-10-08T09:00:00Z", amount_ml=120),
        event("diaper", "2026-10-08T10:00:00Z", diaper_kind="both"),
        event("sleep", "2026-10-08T11:30:00Z", "2026-10-08T12:40:00Z", sleep_kind="nap"),
        event("bottle", "2026-10-08T13:00:00Z", amount_ml=180),
        event("sleep", "2026-10-08T19:20:00Z", None, sleep_kind="night"),
    ]


def dst_autumn():
    # 25 October 2026 has 25 hours in Madrid (clocks go back at 01:00Z).
    return [
        event("sleep", "2026-10-24T18:30:00Z", "2026-10-25T05:30:00Z", sleep_kind="night"),
        event("bottle", "2026-10-24T22:00:00Z", amount_ml=100),   # 00:00 local on the 25th
        event("bottle", "2026-10-25T00:30:00Z", amount_ml=110),   # 02:30, first time
        event("bottle", "2026-10-25T01:30:00Z", amount_ml=120),   # 02:30, second time
        event("sleep", "2026-10-25T09:00:00Z", "2026-10-25T10:15:00Z", sleep_kind="nap"),
        event("diaper", "2026-10-25T22:59:59Z", diaper_kind="dirty"),  # 23:59:59 local, still the 25th
        event("bottle", "2026-10-25T23:00:00Z", amount_ml=130),   # 00:00 local on the 26th
        event("sleep", "2026-10-25T19:00:00Z", "2026-10-26T06:00:00Z", sleep_kind="night"),
    ]


def dst_spring():
    # 29 March 2026 has 23 hours in Madrid (clocks go forward at 01:00Z).
    return [
        event("sleep", "2026-03-28T19:00:00Z", "2026-03-29T05:00:00Z", sleep_kind="night"),
        event("bottle", "2026-03-28T23:00:00Z", amount_ml=90),    # 00:00 local on the 29th
        event("bottle", "2026-03-29T21:59:00Z", amount_ml=95),    # 23:59 local on the 29th
        event("bottle", "2026-03-29T22:00:00Z", amount_ml=99),    # 00:00 local on the 30th: outside
        event("sleep", "2026-03-29T10:00:00Z", "2026-03-29T11:00:00Z", sleep_kind="nap"),
    ]


def overlap_travel():
    return [
        # Two phones recorded the same nap; the union must be counted once.
        event("sleep", "2026-10-08T08:00:00Z", "2026-10-08T09:00:00Z", sleep_kind="nap"),
        event("sleep", "2026-10-08T08:30:00Z", "2026-10-08T09:20:00Z", sleep_kind="nap"),
        # Touching sessions are two naps.
        event("sleep", "2026-10-08T12:00:00Z", "2026-10-08T12:30:00Z", sleep_kind="nap"),
        event("sleep", "2026-10-08T12:30:00Z", "2026-10-08T13:00:00Z", sleep_kind="nap"),
        # A nap swallowed by night sleep becomes night; the session is still open.
        event("sleep", "2026-10-08T18:00:00Z", None, sleep_kind="night"),
        event("sleep", "2026-10-08T18:10:00Z", "2026-10-08T18:40:00Z", sleep_kind="nap"),
        # Recorded while travelling: the hour of day follows the event's own zone.
        event("bottle", "2026-10-08T14:00:00Z", tz="America/New_York", amount_ml=140),
        # An unknown zone falls back to the reporting zone.
        event("bottle", "2026-10-08T15:00:00Z", tz="Mars/Olympus", amount_ml=60),
        # Incomplete rows are ignored rather than counted as zero.
        event("bottle", "2026-10-08T16:00:00Z"),
        event("diaper", "2026-10-08T16:30:00Z"),
        event("note", "2026-10-08T16:45:00Z"),
    ]


def month():
    rng = random.Random(20261008)
    events = []
    first = datetime(2026, 9, 2, tzinfo=MADRID)

    def local(day, hour, minute=0):
        return first + timedelta(days=day, hours=hour, minutes=minute)

    bedtime = local(-1, 20, 5)
    for d in range(37):
        # Night: bedtime the evening before until about 07:00, some nights with a waking.
        wake = local(d, 7) + timedelta(minutes=rng.randint(-25, 25))
        if rng.random() < 0.4:
            waking = local(d, 3) + timedelta(minutes=rng.randint(-60, 60))
            events.append(event("sleep", utc(bedtime), utc(waking), sleep_kind="night"))
            events.append(event("bottle", utc(waking + timedelta(minutes=5)), amount_ml=rng.choice([90, 100, 110, 120])))
            events.append(event("sleep", utc(waking + timedelta(minutes=rng.randint(15, 35))), utc(wake), sleep_kind="night"))
        else:
            events.append(event("sleep", utc(bedtime), utc(wake), sleep_kind="night"))
        # Naps: wake windows grow through the day; a long window tends to shorten the nap.
        t = wake
        for ordinal, base in enumerate([(95, 65), (120, 85), (140, 40)], start=1):
            window = base[0] + rng.randint(-15, 20) + (d // 10) * 5
            start = t + timedelta(minutes=window)
            duration = max(15, base[1] + rng.randint(-20, 20) - max(0, window - base[0]) // 2)
            if ordinal == 3 and rng.random() < 0.15:
                continue  # a day with only two naps
            end = start + timedelta(minutes=duration)
            events.append(event("sleep", utc(start), utc(end), sleep_kind="nap"))
            t = end
        bedtime = max(t + timedelta(minutes=rng.randint(150, 190)), local(d, 19, 30))
        # Bottles about every three hours through the day.
        feed = wake + timedelta(minutes=rng.randint(10, 25))
        while feed < local(d, 20):
            events.append(event("bottle", utc(feed), amount_ml=rng.choice([120, 130, 150, 150, 160, 180])))
            feed += timedelta(minutes=rng.randint(165, 200))
        for _ in range(rng.randint(5, 7)):
            at = local(d, 7) + timedelta(minutes=rng.randint(0, 13 * 60))
            events.append(event("diaper", utc(at), diaper_kind=rng.choice(["wet", "wet", "wet", "dirty", "both"])))
    return events


def main():
    write("empty", "Europe/Madrid", "2026-10-08", 7, "2026-10-08T12:00:00Z", [])
    write("basic_day", "Europe/Madrid", "2026-10-08", 1, "2026-10-08T20:00:00Z", basic_day())
    write("dst_autumn", "Europe/Madrid", "2026-10-26", 3, "2026-10-27T08:00:00Z", dst_autumn())
    write("dst_spring", "Europe/Madrid", "2026-03-29", 2, "2026-03-30T08:00:00Z", dst_spring())
    write("overlap_travel", "Europe/Madrid", "2026-10-08", 1, "2026-10-08T19:00:00Z", overlap_travel())
    events = month()
    for days in (7, 14, 30):
        write(f"month_{days}d", "Europe/Madrid", "2026-10-08", days, "2026-10-08T13:10:00Z", events)
    # The same month seen from another reporting zone: every day boundary moves.
    write("month_14d_new_york", "America/New_York", "2026-10-07", 14, "2026-10-08T13:10:00Z", events)


if __name__ == "__main__":
    main()

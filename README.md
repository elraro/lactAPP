# Baby Tracker

A private, offline-first baby tracker for a single family: bottles, diapers,
sleep, measurements, deterministic statistics, a next-nap estimate, and optional
AI explanations.

- **Android app** — Kotlin, Jetpack Compose, Room. Works without a connection.
- **Server** — one Go binary with a REST API.
- **Database** — PostgreSQL.

No public registration, no social features, no ads, no analytics.

## Status

| Phase | Scope | State |
|---|---|---|
| 0 | Repository bootstrap | done |
| 1 | Architecture | **proposed — awaiting review** |
| 2 | Backend foundation | not started |
| 3 | Android foundation | not started |
| 4 | Core tracking | not started |
| 5 | Synchronization | not started |
| 6 | Statistics | not started |
| 7 | Sleep prediction | not started |
| 8 | AI | not started |
| 9 | Polish | not started |

## Documentation

Start with [AGENTS.md](AGENTS.md) (project rules), then:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — overview and open decisions
- [docs/DATABASE.md](docs/DATABASE.md)
- [docs/API.md](docs/API.md)
- [docs/SYNC.md](docs/SYNC.md)
- [docs/STATISTICS.md](docs/STATISTICS.md)
- [docs/AI.md](docs/AI.md)

## Layout

```
android/    Android app (Phase 3)
server/     Go API (Phase 2)
deploy/     Reverse proxy and operations files
docs/       Design documents
```

## Development prerequisites

- Go (current stable) and Docker with Compose — for the server
- JDK and the Android SDK (platform 35) — for the app

Configuration is by environment variables; copy `.env.example` to `.env`.
Build and run instructions are added with each component.

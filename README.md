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
| 1 | Architecture | done (approved 2026-10-08) |
| 2 | Backend foundation | done |
| 3 | Android foundation | done |
| 4 | Core tracking | done |
| 5 | Synchronization | next |
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
android/    Android app
server/     Go API
deploy/     Reverse proxy and operations files
docs/       Design documents
```

## Development prerequisites

- Go (current stable) and Docker with Compose — for the server
- JDK and the Android SDK (platform 35) — for the app

## Running the server

```sh
cp .env.example .env          # set POSTGRES_PASSWORD (also inside BT_DATABASE_URL) and BT_JWT_SIGNING_KEY
docker compose up -d          # PostgreSQL + API on http://127.0.0.1:8080
```

There is no sign-up. Create the family and its first parent from the server:

```sh
docker compose exec api /babytracker admin create-family \
    -name "Our family" -timezone Europe/Madrid -email you@example.com -display-name "You"
```

This prints a one-time code with which that parent chooses a password in the app.
The second parent is invited from inside the app. A forgotten password is reset with
`admin reset-password -email …`.

For HTTPS, point a DNS name at the host, set `BT_PUBLIC_HOSTNAME` and
`BT_TRUSTED_PROXIES`, and start with `docker compose --profile proxy up -d`.

## Developing the server

```sh
cd server
export BT_TEST_DATABASE_URL='postgres://babytracker:change-me@localhost:5432/postgres?sslmode=disable'
make check                    # format, vet, staticcheck, sqlc diff, tests (race), build
```

Database tests create and drop their own databases on that server; without the
variable they are skipped.

## Developing the app

```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties    # or set ANDROID_HOME
./gradlew ktlintCheck detekt lint test assembleDebug
```

The debug build accepts a plain `http://` server address (for example
`http://10.0.2.2:8080` from the emulator); the release build only accepts HTTPS.

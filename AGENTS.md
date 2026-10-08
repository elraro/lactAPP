# AGENTS.md

Rules for anyone — human or coding agent — working in this repository.
Read this file fully before changing anything.

## 1. What this is

A **private** baby tracker for one family per account: an offline-first Android
app (Kotlin, Compose), a single Go REST server, and PostgreSQL. Parents record
bottles, diapers, sleep and measurements; the system derives statistics and a
next-nap estimate; an optional AI explains those results.

**Current phase:** 2 complete (backend: auth, families, babies, events, sync push/pull).
Next: Phase 3 — Android foundation. Statistics, prediction and AI do not exist yet.
Update this line when a phase completes.

## 2. Source of truth

| Topic | Document |
|---|---|
| Structure, stack, auth, time, localization, open decisions | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Schema | [docs/DATABASE.md](docs/DATABASE.md) |
| Endpoints, error codes | [docs/API.md](docs/API.md) |
| Sync protocol, conflicts, queue | [docs/SYNC.md](docs/SYNC.md) |
| Metric definitions, prediction | [docs/STATISTICS.md](docs/STATISTICS.md) |
| AI provider, context, safety | [docs/AI.md](docs/AI.md) |

If code and a document disagree, that is a bug in one of them: fix it in the same
change. **Never make a project-wide architectural decision silently** — propose it,
get it approved, record it in the relevant document.

## 3. The hierarchy that must never be reversed

```
Raw events → Deterministic statistics → Predictions / patterns → AI interpretation
```

- Events are the only source of truth. Do not store derived values (durations,
  daily totals) as primary data. Caches are allowed if they can be rebuilt.
- Statistics and predictions are pure, deterministic functions. No LLM, no system
  clock, no I/O inside `server/internal/stats`, `server/internal/predict`, `android/stats`.
- The AI receives statistics and returns prose. It never supplies a number that
  the app presents as a fact.

## 4. Repository layout

```
AGENTS.md  README.md  .env.example  docker-compose.yml
docs/        the documents above
server/      Go module: cmd/babytracker, internal/{config,httpapi,auth,syncsvc,store,apperr,testutil}, migrations/
             (internal/{stats,predict,ai} arrive in Phases 6–8)
android/     Gradle project: app/ (UI, data, sync), stats/ (pure Kotlin)
testdata/    stats/ golden fixtures shared by the Go and Kotlin engines
deploy/      reverse proxy config, backup scripts
```

## 5. How to work

1. Work in small increments, one phase at a time (phases are listed in README.md).
   Do not start a phase before the previous one meets the Definition of Done.
2. Before finishing any change, run the checks for every component you touched
   (§6) and fix what fails. Do not leave known-broken code; do not disable a test
   or a lint rule to get green.
3. When several reasonable approaches exist, state them briefly and recommend one.
   Prefer the simplest thing that satisfies the requirement. No speculative
   abstraction: no interface with one implementation unless it is a test seam or
   a documented extension point (`ai.Provider`).
4. Update documentation in the same commit as the behaviour it describes.

### Definition of Done

Implemented · tests written and passing · static analysis clean · build passes ·
docs updated · strings present in English **and** Spanish · offline behaviour
considered · errors handled and surfaced to the user · security implications considered.

## 6. Commands

| Component | Check |
|---|---|
| Server | `cd server && make check` — gofmt, `go vet`, `staticcheck`, `sqlc diff`, `go test -race`, build |
| Server, after editing `internal/store/queries/*.sql` or a migration | `make generate` and commit the generated code |
| Android (from Phase 3) | `cd android && ./gradlew ktlintCheck detekt lint test assembleDebug` |
| Stack | `docker compose up -d` |

Database tests need `BT_TEST_DATABASE_URL` pointing at a PostgreSQL 17 server on which
the user may create databases; each test creates and drops its own. **Without it they
are skipped, which does not count as passing** — do not report the server as verified
from a run with skips. Tools: `go install github.com/sqlc-dev/sqlc/cmd/sqlc@latest`,
`go install honnef.co/go/tools/cmd/staticcheck@latest`.

## 7. Backend rules

- One Go binary. No microservices, no message queue, no cache server.
- All persistent state in PostgreSQL. Keep handlers stateless.
- SQL lives in `.sql` files compiled by sqlc; always parameterized. No string-built SQL.
- **Tenant isolation:** every store function takes `familyID` and filters by it.
  `family_id` comes from the verified token only — never from a URL, query or body.
  Another family's resource is `404`. Every new endpoint gets a cross-family test.
- Schema changes only through a new migration file. Never edit an applied migration.
- Errors returned to clients are `{error:{code,…}}` with stable codes from docs/API.md.
  The server returns **no user-facing text** (AI output excepted).
- Tests that involve SQL run against real PostgreSQL, not mocks.
- Validation of synced entities lives in `internal/syncsvc/validate.go` and mirrors the
  CHECK constraints of the migration. Change both together.
- Time comes from the injected `now` function, never `time.Now()` inside services, so
  expiry and grace-window behaviour stays testable.

## 8. Android rules

- The UI reads and writes **Room only**. No screen waits for the network to
  complete a user action. No Retrofit call from a ViewModel except login, AI and
  the family-settings toggle (documented online-only operations).
- Every local write and its `pending_mutations` row go in **one Room transaction**.
- Layers: `ui` → `domain` (only where logic exists) → `data`. No pass-through use cases.
- State is exposed as `StateFlow<UiState>`; composables are stateless where practical.
- `android/stats` must not import `android.*` or `androidx.*`.
- Main-screen actions must stay reachable with one thumb: primary touch targets
  ≥ 56 dp, logging a bottle or a diaper in ≤ 2 taps.
- Support light and dark themes; never hard-code colors outside the theme.
- Every interactive element and icon has a localized `contentDescription`
  (or is explicitly decorative).

## 9. Sync rules (summary — docs/SYNC.md is authoritative)

- Entity IDs and mutation IDs are UUIDv7 generated on the device.
- All writes to syncable data go through `POST /sync/push`. Do not add another write path.
- A mutation that has been sent once is immutable; retries resend it byte-identical.
- The server decides order (`seq`), never client clocks. `started_at`/`ended_at`
  are user-facing times and are never rewritten by the server.
- Deletes are tombstones. Never physically delete syncable rows.
- The pull cursor is stored in the same transaction that applies the page.
- Never implement "download everything and replace the local database".

## 10. Localization rules

- Languages: **English (default/fallback)** and **Spanish (complete)**.
- **No user-visible string literals in Kotlin/Compose.** Everything goes through
  `stringResource` / `pluralStringResource`. This includes content descriptions,
  error messages, empty states, chart labels, notifications and units.
- Adding a string means adding it to `values/strings.xml` **and**
  `values-es/strings.xml` in the same commit. Lint (`HardcodedText`,
  `MissingTranslation`, `ExtraTranslation`) is configured as error.
- Use `<plurals>` for counts and positional arguments (`%1$s`, `%2$d`) for
  values. Never build a sentence by concatenating fragments.
- Format dates, times and numbers with locale-aware formatters; respect the
  device's 12/24-hour setting. Never format with a fixed pattern such as `"HH:mm"`
  for display.
- Spanish text is written as natural Spanish, not word-for-word translation.
  Do not mix languages on a screen.
- Server error **codes** are mapped to string resources in the app.
- Language choice uses per-app locales (System default / English / Español).

## 11. Time rules

- Store instants in UTC; store the IANA zone of the recording device on each event.
- Display in the device's current zone and selected locale.
- Use `java.time` (Android) and `time` with `time.LoadLocation` (Go). Never do
  day arithmetic by adding 24 h; use calendar operations in the zone.
- Any code that buckets by day needs tests for: midnight crossing, both DST
  transitions (use `Europe/Madrid`), and an event recorded in a different zone.

## 12. Security and privacy rules

- Never commit secrets, real credentials, keystores, database dumps or real baby data.
  Test fixtures use synthetic data.
- Never log passwords, tokens, API keys, request/response bodies, names, notes,
  or event contents. Log IDs and outcomes.
- Passwords: Argon2id. Refresh tokens: stored hashed, rotated on use.
- No third-party analytics, crash reporting, ads or push services without an
  approved architecture change.
- No data goes to an AI provider unless the operator configured one **and** the
  family enabled AI. The AI context never contains names, notes or raw event lists.
- AI output must never diagnose; the prompt rules in docs/AI.md are mandatory.
- Release builds forbid cleartext HTTP.

## 13. Testing requirements

Beyond normal unit tests, these scenarios must be covered where the code exists:
offline create/edit/delete · duplicate and replayed requests · two devices editing
the same row · edit vs. delete · partial batch failure · network timeout mid-sync ·
token expiry mid-sync · sleep across midnight · DST days · device clock skew ·
overlapping sleep sessions · cross-family access on every endpoint · statistics
golden fixtures passing in both Go and Kotlin.

## 14. Git

- Small, logical commits after each completed step. Conventional prefixes:
  `feat(server)`, `feat(android)`, `feat(sync)`, `feat(stats)`, `fix(...)`,
  `docs`, `test(...)`, `chore`.
- No unrelated changes in one commit. No build output, local databases, `.env`,
  `local.properties` or IDE state.

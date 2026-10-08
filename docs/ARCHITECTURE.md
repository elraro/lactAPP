# Architecture

> **Status: APPROVED 2026-10-08.** The server (Phase 2), the Android foundation (Phase 3) and local tracking
> (Phase 4: bottles, diapers, sleep, timeline, editing — stored and queued on the
> device, not yet uploaded) are implemented.
> Section 10 records the decisions that were taken.

## 1. Guiding principle

```
Raw events  →  Deterministic statistics  →  Predictions / patterns  →  AI interpretation
```

Each layer only reads from the layers to its left. Events in the database are the
only source of truth. Statistics are reproducible pure functions of events. The AI
receives statistics and returns prose; it never produces a number the app displays
as a fact.

## 2. System overview

```
┌──────────────── Android device (one per parent) ────────────────┐
│  Compose UI ─ ViewModel ─ Repository ─┬─ Room (source of truth  │
│                                       │   for the UI)           │
│                         Stats engine ─┘                         │
│                         Sync engine ── pending_mutations queue  │
└───────────────────────────────┬─────────────────────────────────┘
                                │ HTTPS, JSON, /api/v1
┌───────────────────────────────▼─────────────────────────────────┐
│  Go API (single binary, stateless)                              │
│  http → auth middleware → handlers → services → store (pgx)     │
│  services: auth · sync · stats · ai                             │
└───────────────┬──────────────────────────────┬──────────────────┘
                │                              │ only if AI enabled
         PostgreSQL 17                  AI provider (Anthropic /
                                        OpenAI-compatible / Ollama)
```

- The Android UI reads and writes **only** Room. Network is never on the path of a
  user action.
- The sync engine is the only component that talks to the sync endpoints.
- The server is one Go process. All state (including refresh tokens and the
  idempotency log) lives in PostgreSQL; the only in-memory state is the auth rate
  limiter, which is acceptable for a single-instance deployment.

## 3. Tenancy and privacy model

```
Family 1 ──< User >── Device
   │
   └──< Baby ──< Event
```

- A user belongs to exactly **one** family. All members are equal (no roles in v1).
- Every syncable row carries `family_id`. Every query in the store layer takes
  `family_id` as a mandatory argument taken from the verified access token, never
  from the request body or URL.
- A resource belonging to another family returns `404`, not `403`, so IDs cannot
  be probed.
- No public registration. Families are created by the server operator through a
  CLI command; additional parents join through a single-use invite code
  (see [API.md](API.md) §2).
- No analytics SDKs, no crash-reporting SDKs, no push provider (FCM) in v1.

## 4. Backend

| Concern | Choice | Why |
|---|---|---|
| Language | Go (current stable) | Required. |
| HTTP | `net/http` with the Go 1.22+ pattern router | Method + path patterns are built in; no framework needed. |
| DB access | `pgx/v5` + `sqlc` | SQL stays explicit and parameterized; generated typed code, no ORM magic. |
| Migrations | `goose`, SQL files embedded in the binary, run on startup | One artifact to deploy; `migrate` subcommand for manual control. |
| Config | Environment variables (`BT_*`), validated at startup | Required. Fail fast on missing secrets. |
| Logging | `log/slog`, JSON in production | Structured; request ID, user/family IDs only. Never bodies, tokens, names, notes. |
| Password hashing | Argon2id (OWASP parameters), PHC string format | Current recommendation; parameters stored per hash so they can be raised. |
| Tokens | 15 min JWT access token (HS256) + opaque rotating refresh token | See §6. |
| Tests | `go test`; DB tests against a real PostgreSQL via `BT_TEST_DATABASE_URL` | No mocks for SQL: sync and isolation bugs live in the queries. |
| Lint | `go vet`, `staticcheck`, `gofmt` | |

Package layout (`server/`):

```
cmd/babytracker/        main: serve | migrate | admin create-family | admin reset-password
internal/config/        env parsing + validation
internal/httpapi/       router, middleware (auth, request id, logging, rate limit), handlers, DTOs
internal/auth/          hashing, tokens, invites
internal/syncsvc/       push (mutation application) and pull (change feed)
internal/apperr/        error type with the stable codes of API.md
internal/testutil/      throwaway PostgreSQL database per test
internal/stats/         (Phase 6) pure functions over []Event — no DB, no clock, no I/O
internal/predict/       (Phase 7) next-sleep model — pure, depends on stats
internal/ai/            (Phase 8) AIProvider interface, providers, context builder, prompt
internal/store/         sqlc-generated queries + thin transaction helpers
migrations/             goose SQL
```

`stats` and `predict` take events, a time zone, and an explicit "now" as arguments.
That is what makes them deterministic and testable.

## 5. Android

| Concern | Choice | Why |
|---|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose | Required. Dynamic color on Android 12+, fixed palette below. |
| DI | Hilt | Standard, integrates with ViewModel and WorkManager. |
| Local DB | Room (KSP) | Required. Flows drive the UI. |
| Network | Retrofit + OkHttp + kotlinx.serialization | Mature; OkHttp authenticator handles token refresh. |
| Background | WorkManager | Sync with network constraint and backoff. |
| Settings | DataStore (Preferences) | Language is handled by the platform, see §8. |
| Token storage | DataStore, values encrypted with an Android Keystore AES-GCM key | `security-crypto` is deprecated; this is the same mechanism without the dependency. |
| Charts | Vico (Compose) | Compose-native, themable, no View interop. Alternative: hand-drawn Canvas — more work, no dependency. |
| Min / target / compile SDK | 26 / 36 / 37 | 26 gives `java.time` without desugaring; current AndroidX requires compiling against 37. |
| Application ID | `eu.elraro.babytracker` | Change before a first release if another ID is wanted; it cannot change afterwards. |
| Build | Gradle 9.8 wrapper, AGP 9.4 (built-in Kotlin), versions in `gradle/libs.versions.toml`; compiles with a JDK 21 toolchain | |
| Tests | JUnit, Turbine, Room in-memory, MockWebServer, Robolectric where a Context is needed | |
| Lint | Android Lint with warnings as errors (hard-coded text, missing/extra translation, plural candidates), detekt, ktlint; Kotlin warnings are errors | |

Gradle modules:

```
android/
  app/      UI, ViewModels, repositories, Room, sync, DI
  stats/    (Phase 6) pure Kotlin/JVM: statistics + prediction. No Android imports.
```

Two modules, not twenty. `stats` is separate only because the compiler then
enforces that it stays pure, and its tests run in milliseconds on the JVM.

Layering inside `app` (package by feature under `ui/`, shared `data/` and `domain/`):

```
ui/<feature>/      Screen composables + ViewModel (StateFlow<UiState>); ui/common for shared pieces
domain/            Pure Kotlin: Event and its validation, SleepRules (nap/night),
                   Timeline (rows from events), Ids (UUIDv7), TimeSource (clock + zone)
data/local/        Room entities, DAOs
data/remote/       Retrofit API, DTOs, ApiResult, interceptors, token authenticator
data/session/      SessionStore (DataStore) and the Keystore cipher for tokens
data/repository/   AuthRepository, EventRepository, BabyRepository, mutation building
data/sync/         (Phase 5) SyncEngine, SyncWorker, mutation queue, rebase logic
di/                Hilt modules
```

A ViewModel calls a repository directly. There are no use-case classes: the rules
that need them (validation, sleep overlap, suggested sleep kind) are pure functions in
`domain/` that `EventRepository` applies inside its write transaction, so no caller
can skip them.

### Session and network

- The server address is typed at sign-in, so Retrofit is built against a placeholder and
  an interceptor resolves the real base URL per request.
- Network calls return `ApiResult` (`Ok` / `Failure(Server(code) | Network | Unexpected)`);
  exceptions do not cross the data layer. The UI maps codes to string resources.
- A 401 triggers one token refresh, shared by calls failing together. The refresh is
  executed synchronously inside the authenticator: an enqueued call would wait for an
  OkHttp per-host slot held by the very calls waiting for it (deadlock, covered by a test).
- If the server rejects the refresh token the session *expires*: tokens are removed,
  local data and the account identity stay, and the login screen explains what happened.
- Explicit sign-out deletes all local data. Signing in as a different family also
  wipes the previous family's local data before the new session is stored.
- Android backup and device transfer are disabled: the server is the copy, and tokens
  are bound to the device's Keystore.

### Write path (every user action)

1. Repository opens one Room transaction: write the entity, append a row to
   `pending_mutations`.
2. Room Flow re-emits; the UI updates. This is the end of the user-visible action.
3. Repository asks the sync engine to run "soon" (debounced unique work).

### Tracking screen (Phase 4)

- **Today** shows the latest feed, diaper and sleep state with one action each, then
  the day's timeline, newest first, with a type filter and previous/next day.
  A bottle or diaper is two taps (action, then amount or kind); sleep and wake are one.
- The summary cards always describe *now*; only the timeline follows the day being browsed.
- A sleep session is one row in the database and two rows in the timeline (fell asleep,
  woke up), each on the day it happened.
- Tapping a row opens the editor (time, details, notes, delete). Delete is undoable
  from the snackbar.
- The first run asks for the baby's name and date of birth.
- Not built yet: measurement and note entry, editing the baby profile, a baby switcher.
  Such events arriving from another device are listed but open no dedicated form.

### Sync triggers

App foreground · after any local write · every 30 s while the app is in the
foreground · WorkManager periodic (15 min, network required) · pull-to-refresh.
No push notifications: the second parent sees a change within 30 s if their app
is open, or on next open.

## 6. Authentication

- **Login**: email + password + device descriptor → access token + refresh token.
- **Access token**: JWT, 15 min, claims `sub` (user), `fam`, `dev`, `exp`. Verified
  without a DB round trip.
- **Refresh token**: 256-bit random, stored only as SHA-256 hash, bound to a device,
  90-day sliding expiry, **rotated on every use**.
  - Reuse of an already-rotated token revokes the whole chain for that device
    (stolen-token detection)...
  - ...except within a 60 s grace window, where another successor is issued.
    Without this, a refresh whose response is lost on a bad connection would log
    the parent out.
- **Logout** revokes the device's chain. **Password change** revokes all other
  devices. A device list in Settings allows revoking a lost phone. Revocation stops
  refresh immediately; an access token already issued stays valid until it expires
  (≤ 15 min), which is the price of verifying access tokens without a database lookup.
- A device ID is bound to the family that first signed in with it.
- **Rate limiting**: login, refresh and invite redemption are limited per IP and
  per e-mail; failed logins use a constant-time comparison path whether or not the
  e-mail exists.
- **Provisioning**: `babytracker admin create-family --name … --email …` creates
  the family and first user and prints a one-time password-setup code (valid 7 days).
  `babytracker admin reset-password --email …` issues a new one for an existing user. Other
  parents: an authenticated member generates an invite code (single use, 48 h),
  the new parent redeems it in the app and chooses their own password.

## 7. Time

| What | Representation |
|---|---|
| Instants (`started_at`, `ended_at`, `created_at`, …) | UTC (`timestamptz` / epoch millis in Room / RFC 3339 `Z` on the wire) |
| Where the event happened | `timezone` column: IANA zone ID of the device when the event was recorded (e.g. `Europe/Madrid`) |
| Dates without time (date of birth) | `date`, no zone |

- **Display**: always the device's current zone and the selected locale.
- **Statistics by day**: a "day" is a local calendar day in a *reporting zone*
  (the device zone on Android; the `tz` query parameter on the server, defaulting
  to the family's zone). Day boundaries come from zone rules, so DST days are 23 h
  or 25 h long and are handled without special cases.
- **Time-of-day analysis** (nap start time, feed distribution) uses each event's
  *own* `timezone`, so a trip abroad does not smear the pattern.
- **Midnight**: a sleep session is one row with two instants. Per-day totals clip
  the interval to each day; nothing is stored per day.
- **Clock skew**: `started_at` is what the parent saw on their phone and is never
  rewritten. Ordering of concurrent edits uses the server sequence, never client
  clocks. Every response carries `server_time`; if the device differs by more than
  2 minutes the app shows a localized warning suggesting automatic time be enabled.

## 8. Localization

- English is the default (`res/values/strings.xml`); Spanish is complete
  (`res/values-es/strings.xml`). Lint treats `HardcodedText`, `MissingTranslation`
  and `ExtraTranslation` as errors, so an untranslated string fails the build.
- Language selection uses per-app locales (`AppCompatDelegate.setApplicationLocales`
  with `locales_config.xml`): *System default* / *English* / *Español*. The platform
  persists the choice and it also appears in the system's per-app language settings
  on Android 13+.
- Plurals use `<plurals>`; sentences with values use positional format arguments
  (`%1$s`). No string concatenation, no sentence fragments.
- Dates, times and numbers are formatted with `java.time` / `NumberFormat` for the
  current locale and the device's 12/24 h preference. Durations have their own
  resources (`1 h 32 min` / `1 h 32 min`), not hand-built strings.
- **The server never returns user-facing text.** Errors are stable codes
  (`invalid_credentials`, `invite_expired`, …) that the app maps to string resources.
  The only exception is AI output, which is generated in the locale the app requests.
- Units are metric (ml, kg, cm) in v1.

## 9. Security checklist (enforced by tests where marked ✔)

- ✔ Every endpoint except login / refresh / invite redemption / health requires a valid access token.
- ✔ Cross-family access to any resource ID returns 404 (one test per endpoint).
- ✔ Sync push cannot write to a baby of another family, and cannot change `family_id`.
- ✔ Replayed mutations do not create duplicates.
- All SQL is parameterized (sqlc); request bodies are size-limited and validated.
- TLS terminates at the reverse proxy (Caddy, optional compose profile). The Android
  release build forbids cleartext traffic; the debug build allows it only for
  `10.0.2.2` and `localhost`.
- Secrets only from the environment. `.env` is git-ignored.
- AI calls happen only if the operator configured a provider **and** the family
  enabled AI. See [AI.md](AI.md).

## 10. Decisions

All recommendations below were **approved on 2026-10-08**. The alternatives are kept
for the record.

| # | Question | Recommendation | Main alternative |
|---|---|---|---|
| D1 | Where are statistics computed? Offline-first says on the device; "the backend builds the AI context" says on the server. | **Both**: Go engine (canonical for API + AI) and Kotlin engine (offline display), kept identical by shared golden fixtures in `testdata/stats/` that both test suites must pass. | Server only; the app caches the last result and shows only simple "today" figures offline. Half the work, but statistics and the nap prediction go stale offline. |
| D2 | One `events` table or one table per event type? | **One table**, `type` discriminator, typed nullable columns, CHECK constraints per type. One timeline query, one sync stream. | Table per type (more joins, N sync streams) or JSONB payload (no constraints). |
| D3 | REST CRUD for events *and* a sync endpoint? | **All writes go through `POST /sync/push`** (batched, idempotent mutations). REST keeps read-only `GET` endpoints. One write path to secure and test. | Classic `POST/PUT/DELETE /events` plus sync: two write paths with separate idempotency logic. |
| D4 | Conflict resolution | **Field-level last-writer-wins in server arrival order; delete wins over update.** Mutations carry only changed fields. | Whole-row LWW (simpler, loses the other parent's edit to a different field). |
| D5 | Overlapping sleep sessions | Device **blocks** overlaps it can see. Overlaps created by two offline devices are **accepted** by the server, flagged in the timeline for review, and statistics use the union of intervals so nothing is double-counted. | Server rejects overlaps — would discard data a parent recorded offline. |
| D6 | Nap vs. night | Auto-suggested from local start time (19:00–06:00 → night), always editable. | Always ask (extra tap at 3 a.m.). |
| D7 | Feeding scope: only bottles are specified. | Implement **bottle** only, with an optional `milk_kind` (formula / breast milk / mixed). The schema leaves room for a `breastfeed` type later. | Add breastfeeding (side + duration) now. |
| D8 | Multiple babies | Model and API support several; UI shows a baby switcher only when there is more than one. | Single baby hard-coded. |
| D9 | AI on/off scope | **Family-level** setting stored on the server, off by default, changeable only online. The data is the family's, so consent is too. | Per-user or per-device toggle. |
| D10 | Server URL | Entered on the login screen (self-hosted), with a build-time default. | Compiled in only. |
| D11 | Notifications | The spec lists them under localization but defines none. **None in v1.** Candidate later: local-only "nap window opening" reminder. | — |
| D12 | Realtime between phones | 30 s foreground polling, no FCM. | Server-sent events later if polling feels slow. |

## 11. Out of scope for v1 (model allows adding later)

Growth percentiles (needs WHO LMS tables and a `sex` field — the column exists,
nullable), imperial units, breastfeeding/solids/medication events, data export,
photo attachments, roles/permissions inside a family.

# REST API

> **Status: APPROVED 2026-10-08.** §1–4 and §7 are implemented; §5 (statistics) and §6 (AI)
> are specified for Phases 6–8 and return 404 until then.

Base path `/api/v1`. JSON only (`Content-Type: application/json`). HTTPS only in
production.

## 1. Conventions

- **Auth**: `Authorization: Bearer <access token>` on everything except the
  endpoints marked *public*.
- **Tenant**: the family is always taken from the token. No endpoint accepts a
  `family_id`.
- **Instants**: RFC 3339 UTC (`2026-10-08T14:32:00Z`). **Dates**: `YYYY-MM-DD`.
  **Zones**: IANA IDs.
- **IDs**: UUID strings.
- **Unknown JSON fields** are rejected on requests (`400`), and must be ignored by
  clients on responses (forward compatibility).
- **Errors**:

  ```json
  { "error": { "code": "invite_expired", "message": "developer-facing English text",
               "fields": { "amount_ml": "out_of_range" } } }
  ```

  `code` is a stable machine identifier; the app maps it to a localized string.
  `message` is for logs and developers and is never shown to users.

  | HTTP | Codes |
  |---|---|
  | 400 | `invalid_request`, `validation_failed` |
  | 401 | `unauthenticated`, `invalid_credentials`, `invalid_grant` |
  | 403 | `ai_disabled`, `ai_not_configured` |
  | 404 | `not_found` (also used for other families' resources) |
  | 409 | `email_taken` |
  | 410 | `cursor_expired`, `invite_expired` |
  | 413 | `payload_too_large` |
  | 429 | `rate_limited` (with `Retry-After`) |
  | 5xx | `internal`, `ai_provider_error` |

- Every response has `X-Request-Id`. Authenticated JSON responses that the sync
  engine consumes include `server_time`.

## 2. Authentication and provisioning

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/login` | public, rate-limited | E-mail + password + device → tokens |
| POST | `/auth/refresh` | public, rate-limited | Rotate refresh token |
| POST | `/auth/logout` | bearer | Revoke this device's tokens |
| POST | `/auth/password` | bearer | Change password; revokes other devices |
| POST | `/auth/invites` | bearer | Create a single-use join code (48 h) |
| POST | `/auth/invites/redeem` | public, rate-limited | Join a family or set first password |
| GET | `/me` | bearer | Current user, family, device |
| GET | `/devices` | bearer | Family's devices |
| DELETE | `/devices/{id}` | bearer | Revoke a device |

```jsonc
// POST /auth/login
{ "email": "a@example.com", "password": "…",
  "device": { "id": "uuid", "name": "Pixel 8", "platform": "android", "app_version": "1.0.0" } }
// 200
{ "access_token": "…", "expires_in": 900, "refresh_token": "…",
  "user": { "id": "…", "email": "…", "display_name": "…" },
  "family": { "id": "…", "name": "…", "timezone": "Europe/Madrid", "ai_enabled": false },
  "server_time": "…" }

// POST /auth/refresh
{ "refresh_token": "…", "device_id": "uuid" }            // 200: same token fields as login

// POST /auth/invites            → 201 { "code": "K7QF-2M9X-PD4T", "expires_at": "…" }
// POST /auth/invites/redeem
{ "code": "K7QF-2M9X-PD4T", "email": "b@example.com", "display_name": "…", "password": "…",
  "device": { … } }                                       // 200: same as login
// A password-setup code (from the admin CLI) needs only code, password and device.
// Unknown, used and expired codes all answer 410 invite_expired.

// POST /auth/password           → 204
{ "current_password": "…", "new_password": "…" }

// GET /devices                  → 200
{ "devices": [ { "id": "…", "user_id": "…", "name": "Pixel 8", "platform": "android",
                 "app_version": "1.0.0", "created_at": "…", "last_seen_at": "…", "revoked_at": null } ] }
```

Password policy: 10–128 characters, no composition rules. Family creation has no
HTTP endpoint; it is a CLI command on the server (see ARCHITECTURE.md §6).

## 3. Family and babies (read)

| Method | Path | Purpose |
|---|---|---|
| GET | `/family` | Family with members |
| PATCH | `/family` | `name`, `timezone`, `ai_enabled` (online-only on purpose: AI consent is not queued offline) |
| GET | `/babies` | Babies of the family |
| GET | `/babies/{id}` | One baby |
| GET | `/babies/{id}/events` | Newest first. `from` (inclusive), `to` (exclusive), `type` (repeatable), `limit` (default 100, ≤500), `page_token`, `include_deleted=true`. Returns `{events, next_page_token}` |

Babies and events are **created and modified through sync push** (§4). The `GET`
endpoints exist for diagnostics, scripts and any future non-offline client.

## 4. Sync

| Method | Path | Purpose |
|---|---|---|
| GET | `/sync?cursor=&limit=` | Changes since cursor |
| POST | `/sync/push` | Apply a batch of mutations |

Full semantics, payloads and status values: [SYNC.md](SYNC.md).

Entity payloads (`data` in pull, `fields` subset in push):

```jsonc
// baby
{ "id": "…", "name": "…", "date_of_birth": "2026-07-01", "sex": null, "notes": null,
  "version": 1, "seq": 3, "created_at": "…", "updated_at": "…", "deleted_at": null }

// event
{ "id": "…", "baby_id": "…", "type": "bottle|diaper|sleep|measurement|note",
  "started_at": "…", "ended_at": null, "timezone": "Europe/Madrid",
  "amount_ml": 120, "milk_kind": null,
  "diaper_kind": null, "sleep_kind": null,
  "weight_g": null, "height_mm": null, "head_mm": null,
  "notes": null,
  "version": 1, "seq": 812, "created_at": "…", "updated_at": "…", "deleted_at": null,
  "created_by_user_id": "…", "created_by_device_id": "…",
  "updated_by_user_id": "…", "updated_by_device_id": "…" }
```

Writable event fields: `baby_id` (create only), `type` (create only), `started_at`,
`ended_at`, `timezone`, the type-specific columns, `notes`. Everything else is
server-assigned and rejected if sent.

## 5. Statistics and prediction

All take `tz` (IANA, default family zone). Definitions: [STATISTICS.md](STATISTICS.md).

| Method | Path | Query |
|---|---|---|
| GET | `/babies/{id}/stats/feeding` | `days=7\|14\|30`, `end=YYYY-MM-DD` (default today) |
| GET | `/babies/{id}/stats/diapers` | same |
| GET | `/babies/{id}/stats/sleep` | same |
| GET | `/babies/{id}/stats/wake-windows` | same |
| GET | `/babies/{id}/stats/growth` | — |
| GET | `/babies/{id}/predictions/next-sleep` | `at` (instant, default now) |

Every metric is returned as an object that carries its own evidence:

```json
{ "wake_window": { "median_s": 6120, "p25_s": 5460, "p75_s": 6900, "sample_size": 18 } }
```

`null` value with `sample_size` below the documented minimum means "not enough
data"; the app renders a localized message.

## 6. AI

| Method | Path | Purpose |
|---|---|---|
| GET | `/ai/status` | `{ "configured": true, "enabled": false, "provider": "anthropic", "remaining_today": 20 }` |
| POST | `/ai/analyses` | `{ "baby_id", "kind": "overview\|sleep\|feeding", "days": 7\|14\|30, "locale": "es" }` |
| GET | `/ai/analyses?baby_id=` | Previous analyses (most recent first) |

Returns `403 ai_not_configured` if the operator set no provider and
`403 ai_disabled` if the family has not enabled AI. Details: [AI.md](AI.md).

## 7. Operations

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/healthz` | public | Process is up |
| GET | `/readyz` | public | Database reachable (migrations run before the server listens) |

## 8. Limits

Request body 1 MB · 200 mutations per push · 500 changes per pull page ·
login 5/min per IP+e-mail and 20/min per IP · refresh 30/min per IP ·
invite redemption 5/min per IP · AI analyses per family per day configurable
(default 20).

# Synchronization protocol

> **Status: APPROVED 2026-10-08.** Server side: `server/internal/syncsvc`. Android side:
> `android/app/.../data/sync` (engine, coalescer, scheduler, monitor). Both implemented.

## 1. Model in one paragraph

Sync is **state-based, with a per-family monotonic change counter**. Devices send
*mutations* (small, idempotent, client-identified changes) to `POST /sync/push` and
receive *the current state of every row changed since their cursor* from
`GET /sync`. The server is the arbiter of order. There is no operation log to
replay and no "download everything and overwrite".

## 2. Identifiers and clocks

| Thing | Who creates it | Purpose |
|---|---|---|
| Entity `id` (UUIDv7) | Device | Row exists locally before the server knows it; creates are idempotent by primary key. |
| `mutation_id` (UUIDv7) | Device | Idempotency key for one change. |
| `device_id` (UUID) | Device, on first launch | Attribution; binds refresh tokens. |
| `seq` | Server | Position in the family's change feed. |
| `version` | Server | Per-row counter; lets the server see that a mutation was based on stale data. |
| `started_at` / `ended_at` | Device (user-visible time) | Domain time. Never rewritten by the server. |
| `created_at` / `updated_at` | Server | Audit only. |

Client clocks are never used to decide which edit wins.

## 3. Pull

```
GET /api/v1/sync?cursor=<opaque>&limit=500
```

```json
{
  "changes": [
    { "entity": "event", "data": { "id": "…", "version": 3, "deleted_at": null, "…": "…" } },
    { "entity": "baby",  "data": { "…": "…" } }
  ],
  "next_cursor": "djE6NGY…",
  "has_more": false,
  "server_time": "2026-10-08T14:32:10Z"
}
```

- The cursor is opaque to the client. It encodes `(protocol version, sync_epoch, seq)`.
- No cursor = start from `seq 0`: the first sync is the same code path as every
  other sync, just longer, paged with `has_more`.
- The server returns rows with `seq > cursor.seq` ordered by `seq`, merged across
  `families`, `babies`, `events`. Each row appears at most once with its **latest**
  state, tombstones included (`deleted_at` set).
- The client applies a page and stores `next_cursor` **in the same Room
  transaction**. A crash mid-sync therefore resumes exactly, never skips.
- Applying a change is an upsert by `id`, ignored if the local row already has an
  equal or higher `version`.
- **Epoch mismatch** (server restored from backup): `410 cursor_expired`. The
  client clears its cursor, pulls from zero, and reconciles in the same transaction
  as the last page: rows the server once confirmed but that are absent from the fresh
  snapshot are removed — unless they carry local changes the server never confirmed
  (queued or rejected). Those, and events that were never uploaded, are queued again
  as new rows in their current state, **babies before their events**, and pushed at once.

## 4. Push

```
POST /api/v1/sync/push
```

```json
{
  "mutations": [
    {
      "mutation_id": "0192…",
      "entity": "event",
      "entity_id": "0192…",
      "op": "upsert",
      "base_version": 0,
      "fields": { "baby_id": "…", "type": "bottle", "started_at": "2026-10-08T14:32:00Z",
                  "timezone": "Europe/Madrid", "amount_ml": 120 }
    }
  ]
}
```

- `op`: `upsert` | `delete` | `restore`.
- `fields` contains **only the fields the user changed** (all required fields on
  create). `base_version` is the version the device had when the user made the
  change (`0` for a create).
- Max 200 mutations per request; the client sends them in creation order.

Response — one result per mutation, same order:

```json
{
  "results": [
    { "mutation_id": "0192…", "status": "applied", "merged": false,
      "data": { "id": "…", "version": 1, "seq": 812, "…": "…" } }
  ],
  "server_time": "…"
}
```

| `status` | Meaning | Client action |
|---|---|---|
| `applied` | Change is durable. `data` is the resulting row. `merged: true` if the row had changed since `base_version`. | Remove from queue, store `data`. |
| `rejected` | Permanent failure, with `error_code` (`validation_failed`, `entity_deleted`, `not_found`, `forbidden`) and, for validation, `fields` (`{"amount_ml": "out_of_range"}`). | Mark mutation `failed`; see §6. |

There is no `duplicate` status on purpose: a replay returns the **recorded
verdict** (`status`, `error_code`, `merged`) together with the row's *current*
state, so the client cannot tell and does not need to. A rejection is as
permanent as an application: retrying a rejected `mutation_id` is never applied
later; the client must issue a new mutation.

### Server algorithm (per mutation, each in its own transaction)

1. Look up `(family_id, mutation_id)` in `applied_mutations`. Found → return the
   stored outcome with the row's current state. **This is the idempotency
   guarantee.**
2. Authorize: the entity (and the referenced baby) must belong to the caller's
   family. `family_id` and attribution columns are set by the server, never read
   from `fields`.
3. (The family row is locked before step 1, which serializes this family's
   writers; `seq` = family counter + 1, consumed only if a row is written.)
4. Apply:
   - `upsert`, row absent → insert; all required fields must be present.
   - `upsert`, row present and live → overwrite the listed fields only.
   - `upsert`, row tombstoned → `rejected: entity_deleted`.
   - `delete` → set `deleted_at`. `restore` → clear it. Both are no-ops (no new
     version, nothing in the feed) if the row is already in that state, and
     `rejected: not_found` if the row does not exist.
   - An ID that exists in another family → `rejected: forbidden`. A `baby_id`
     that is unknown *or* belongs to another family → `rejected: not_found`.
   - `baby_id` and `type` of an event cannot change after creation.
   - Validate the *resulting* row (DB CHECKs + application rules). Failure →
     roll back, record `rejected`.
5. `version += 1`, set `seq`, `updated_*`. Insert the `applied_mutations` row.
   Commit.

One mutation failing never blocks the others in the batch.

## 5. Conflicts

| Situation | Resolution |
|---|---|
| Two devices create different events | No conflict. Both exist. |
| Same event, different fields edited | Both edits survive (each mutation only carries its changed fields). |
| Same event, same field edited | Last mutation **to reach the server** wins. The earlier writer receives the final state on its next pull. |
| Edit vs. delete | **Delete wins.** The editor's mutation is `rejected: entity_deleted`; the app tells the user the item was deleted by another device. |
| Delete vs. delete | Idempotent no-op. |
| Both parents press WAKE on the same open session | Same-field case: the later arrival's `ended_at` wins. Typically seconds apart. |
| Both parents press SLEEP offline | Two sessions exist → overlap, see §7. |

**Conflict detection** is `base_version < current version` at apply time,
reported as `merged: true`. We deliberately do not keep per-field version vectors:
the benefit (telling apart "merged cleanly" from "overwrote a concurrent value")
does not justify the storage and complexity for a two-parent app.

## 6. Client queue

`pending_mutations` is a FIFO table written in the same transaction as the
entity change.

**Coalescing before send** (keeps batches small and avoids pointless traffic):

- An entity the server has never been told about (local `version` 0, nothing of it
  already sent) collapses to its current state: **one create** with all its fields, or
  **nothing at all** if it was deleted again. This also covers "create, delete, undo".
- For an entity the server knows: delete + restore cancel out; consecutive updates
  become one update with the union of fields and the earliest `base_version`.

Coalescing happens only among mutations that have **never been sent**; a mutation
that was sent (even if the response was lost) keeps its `mutation_id` and content
forever, otherwise idempotency would break.

**One sync cycle** (single-flight; guarded by a mutex and unique WorkManager work):

```
1. push pending mutations in batches   ── until queue empty or failure
2. pull pages                          ── until has_more = false
3. record last_success_at, server_time_offset
```

Push goes first so the following pull already includes the server's final verdict
on our own changes.

**Rebase on pull.** If a pulled row still has unsent local mutations, the server
state is stored and the pending `fields` are re-applied on top for display, so the
user's unsynced edit does not flicker away.

**Failure handling**

| Failure | Behaviour |
|---|---|
| No network / timeout / 5xx / 429 | Whole batch stays queued. Exponential backoff 30 s → 1 h (WorkManager), reset on connectivity change or user action. Safe because of §4 step 1. |
| 401 | Refresh token once, retry. If refresh fails with `invalid_grant`: stop syncing, keep all local data and the queue, show the login screen. After login the queue drains. |
| Per-mutation `rejected` | The mutation is kept, marked with its error code, and never sent again. Today shows "the server rejected N changes"; Settings offers *Discard rejected changes*, which drops them and downloads the server's version of those records. `entity_deleted` resolves by itself: the local row is tombstoned and the mutation removed. |
| Whole request refused (400, 413) | The batch is marked rejected as above, so one malformed batch cannot block the queue forever. |
| Partial batch (some applied, response lost) | Retry of the same batch: applied ones return their stored result, the rest are applied now. |
| App killed mid-pull | Cursor only advanced for committed pages; next cycle continues. |

Logging out with a non-empty queue requires explicit confirmation ("3 changes
have not been uploaded and will be lost").

## 7. Sleep overlap

Overlap rule: two non-deleted sleep sessions of the same baby overlap if their
intervals intersect; an open session counts as extending to "now".

1. **On the device** — creating, editing or un-deleting a session that overlaps one
   the device knows about is refused inside the write transaction; the editor shows
   why and keeps the form open so the times can be changed. Sessions that touch
   end-to-start do not overlap. Pressing SLEEP while a session is open is not
   possible (the button is WAKE). *(Implemented, Phase 4. Offering to end the open
   session automatically is a possible refinement.)*
2. **On the server** — overlaps are **accepted**. Rejecting would throw away
   something a parent recorded while offline.
3. **After sync** — the device detects overlaps among pulled rows, shows both in
   the timeline with a "needs review" marker and offers *Merge* (earliest start,
   latest end, delete the other) or manual edit.
4. **Statistics** always use the union of intervals, so an unresolved overlap
   never double-counts sleep. Two open sessions are treated as one that began at
   the earlier start.

### Triggers (Android)

| Trigger | Mechanism |
|---|---|
| After any local change, and after signing in | WorkManager unique one-time work, 2 s debounce, network required, exponential backoff from 30 s |
| App visible | A cycle on resume and every 30 s (`AppViewModel.syncWhileVisible`) |
| App closed | WorkManager periodic work every 15 min, network required |
| User | Pull-to-refresh on Today; *Sync now* in Settings |

Background work is scheduled while a session exists and cancelled on sign-out.

### First synchronization

Until one cycle has completed, Today shows "getting your family's data" instead of
the add-baby form: before the first download the app cannot know whether the other
parent already created the baby, and asking would create a duplicate.

## 8. Required tests

Server (real PostgreSQL): replayed batch returns identical results and creates one
row · concurrent pushes from two devices produce gap-free, ordered feeds · field
merge · edit-vs-delete · cross-family mutation rejected · cursor paging with
`limit=1` visits every row once · epoch mismatch → 410.

Android (Room in-memory + MockWebServer): offline create → row visible, queued ·
timeout then retry sends identical body · coalescing rules · rebase on pull ·
crash between push and pull · 401 → refresh → continue · rejected mutation
surfaces as failed · two simulated devices converge to identical tables.

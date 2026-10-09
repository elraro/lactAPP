-- name: GetBaby :one
SELECT * FROM babies WHERE id = $1 AND family_id = $2;

-- name: GetBabyForUpdate :one
SELECT * FROM babies WHERE id = $1 AND family_id = $2 FOR UPDATE;

-- name: InsertBaby :execrows
INSERT INTO babies (id, family_id, name, date_of_birth, sex, notes, seq,
                    created_by_user_id, created_by_device_id, updated_by_user_id, updated_by_device_id)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $8, $9)
ON CONFLICT (id) DO NOTHING;

-- name: UpdateBaby :exec
UPDATE babies
SET name = $3, date_of_birth = $4, sex = $5, notes = $6, deleted_at = $7,
    seq = $8, version = version + 1, updated_at = now(),
    updated_by_user_id = $9, updated_by_device_id = $10
WHERE id = $1 AND family_id = $2;

-- name: ListBabies :many
SELECT * FROM babies WHERE family_id = $1 AND deleted_at IS NULL ORDER BY date_of_birth, id;

-- name: ListBabyChanges :many
SELECT * FROM babies WHERE family_id = $1 AND seq > $2 ORDER BY seq LIMIT $3;

-- name: GetEvent :one
SELECT * FROM events WHERE id = $1 AND family_id = $2;

-- name: GetEventForUpdate :one
SELECT * FROM events WHERE id = $1 AND family_id = $2 FOR UPDATE;

-- name: InsertEvent :execrows
INSERT INTO events (id, family_id, baby_id, type, started_at, ended_at, timezone,
                    amount_ml, milk_kind, diaper_kind, sleep_kind, weight_g, height_mm, head_mm,
                    notes, seq,
                    created_by_user_id, created_by_device_id, updated_by_user_id, updated_by_device_id)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $17, $18)
ON CONFLICT (id) DO NOTHING;

-- name: UpdateEvent :exec
UPDATE events
SET started_at = $3, ended_at = $4, timezone = $5,
    amount_ml = $6, milk_kind = $7, diaper_kind = $8, sleep_kind = $9,
    weight_g = $10, height_mm = $11, head_mm = $12, notes = $13, deleted_at = $14,
    seq = $15, version = version + 1, updated_at = now(),
    updated_by_user_id = $16, updated_by_device_id = $17
WHERE id = $1 AND family_id = $2;

-- name: ListEventChanges :many
SELECT * FROM events WHERE family_id = $1 AND seq > $2 ORDER BY seq LIMIT $3;

-- name: ListEvents :many
-- Newest first, keyset-paginated on (started_at, id).
SELECT * FROM events
WHERE family_id = sqlc.arg(family_id)
  AND baby_id = sqlc.arg(baby_id)
  AND (sqlc.arg(include_deleted)::boolean OR deleted_at IS NULL)
  AND (sqlc.narg(from_time)::timestamptz IS NULL OR started_at >= sqlc.narg(from_time))
  AND (sqlc.narg(to_time)::timestamptz IS NULL OR started_at < sqlc.narg(to_time))
  AND (cardinality(sqlc.arg(types)::text[]) = 0 OR type = ANY (sqlc.arg(types)::text[]))
  AND (sqlc.narg(before_time)::timestamptz IS NULL
       OR (started_at, id) < (sqlc.narg(before_time)::timestamptz, sqlc.arg(before_id)::uuid))
ORDER BY started_at DESC, id DESC
LIMIT sqlc.arg(row_limit);

-- name: GetAppliedMutation :one
SELECT * FROM applied_mutations WHERE family_id = $1 AND mutation_id = $2;

-- name: InsertAppliedMutation :exec
INSERT INTO applied_mutations (family_id, mutation_id, device_id, entity, entity_id, status, error_code, merged)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8);

-- name: ListEventsTouching :many
-- Live events of a baby that start in [from_time, to_time), plus sleep that began earlier
-- and was still going on. Oldest first. Used by statistics and prediction.
SELECT * FROM events
WHERE family_id = sqlc.arg(family_id)
  AND baby_id = sqlc.arg(baby_id)
  AND deleted_at IS NULL
  AND started_at < sqlc.arg(to_time)
  AND (started_at >= sqlc.arg(from_time)
       OR (type = 'sleep' AND (ended_at IS NULL OR ended_at >= sqlc.arg(from_time))))
ORDER BY started_at, id;

-- name: CreateFamily :one
INSERT INTO families (id, name, timezone, sync_epoch)
VALUES ($1, $2, $3, $4)
RETURNING *;

-- name: GetFamily :one
SELECT * FROM families WHERE id = $1;

-- name: LockFamily :one
-- Serializes all writers of one family; see docs/DATABASE.md §2.
SELECT * FROM families WHERE id = $1 FOR UPDATE;

-- name: SetFamilySyncSeq :exec
UPDATE families SET sync_seq = $2 WHERE id = $1;

-- name: UpdateFamilySettings :one
UPDATE families
SET name = $2, timezone = $3, ai_enabled = $4,
    sync_seq = $5, seq = $5, version = version + 1, updated_at = now()
WHERE id = $1
RETURNING *;

-- name: ListFamilyChanges :many
SELECT * FROM families WHERE id = $1 AND seq > $2;

-- name: CreateUser :one
INSERT INTO users (id, family_id, email, display_name, password_hash)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;

-- name: GetUserByEmail :one
SELECT * FROM users WHERE email = $1;

-- name: GetUser :one
SELECT * FROM users WHERE id = $1;

-- name: ListFamilyUsers :many
SELECT * FROM users WHERE family_id = $1 ORDER BY created_at, id;

-- name: SetUserPassword :exec
UPDATE users SET password_hash = $2, updated_at = now() WHERE id = $1;

-- name: GetDeviceByID :one
SELECT * FROM devices WHERE id = $1;

-- name: GetFamilyDevice :one
SELECT * FROM devices WHERE id = $1 AND family_id = $2;

-- name: InsertDevice :exec
INSERT INTO devices (id, family_id, user_id, name, platform, app_version)
VALUES ($1, $2, $3, $4, $5, $6);

-- name: UpdateDeviceOnLogin :exec
UPDATE devices
SET user_id = $3, name = $4, platform = $5, app_version = $6,
    last_seen_at = now(), revoked_at = NULL
WHERE id = $1 AND family_id = $2;

-- name: TouchDevice :exec
UPDATE devices SET last_seen_at = now() WHERE id = $1;

-- name: ListFamilyDevices :many
SELECT * FROM devices WHERE family_id = $1 ORDER BY created_at, id;

-- name: RevokeFamilyDevice :execrows
UPDATE devices SET revoked_at = COALESCE(revoked_at, now()) WHERE id = $1 AND family_id = $2;

-- name: InsertRefreshToken :exec
INSERT INTO refresh_tokens (id, device_id, user_id, token_hash, expires_at)
VALUES ($1, $2, $3, $4, $5);

-- name: GetRefreshTokenForUpdate :one
SELECT * FROM refresh_tokens WHERE token_hash = $1 FOR UPDATE;

-- name: MarkRefreshTokenUsed :exec
UPDATE refresh_tokens
SET used_at = COALESCE(used_at, sqlc.arg(used_at)::timestamptz), replaced_by = sqlc.arg(replaced_by)
WHERE id = sqlc.arg(id);

-- name: RevokeDeviceTokens :exec
UPDATE refresh_tokens SET revoked_at = now() WHERE device_id = $1 AND revoked_at IS NULL;

-- name: RevokeUserTokens :exec
UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL;

-- name: RevokeUserTokensExceptDevice :exec
UPDATE refresh_tokens SET revoked_at = now()
WHERE user_id = $1 AND device_id <> $2 AND revoked_at IS NULL;

-- name: InsertInvite :exec
INSERT INTO invites (id, family_id, code_hash, purpose, user_id, created_by, expires_at)
VALUES ($1, $2, $3, $4, $5, $6, $7);

-- name: GetInviteForUpdate :one
SELECT * FROM invites WHERE code_hash = $1 FOR UPDATE;

-- name: RedeemInvite :exec
UPDATE invites SET redeemed_at = now() WHERE id = $1;

-- name: ExpireUserSetPasswordInvites :exec
UPDATE invites SET expires_at = now()
WHERE user_id = $1 AND purpose = 'set_password' AND redeemed_at IS NULL AND expires_at > now();

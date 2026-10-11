-- Housekeeping (docs/DATABASE.md §4, "Retention"). Family data is never pruned.

-- name: DeleteOldAppliedMutations :execrows
DELETE FROM applied_mutations WHERE applied_at < $1;

-- name: UnlinkOldRefreshTokens :exec
-- A token may point at its successor; clear links into the rows about to be deleted.
UPDATE refresh_tokens SET replaced_by = NULL
WHERE replaced_by IN (
  SELECT id FROM refresh_tokens r
  WHERE r.expires_at < $1 OR r.used_at < $1 OR r.revoked_at < $1
);

-- name: DeleteOldRefreshTokens :execrows
-- Tokens that stopped being usable (expired, rotated or revoked) before the cut-off.
DELETE FROM refresh_tokens
WHERE expires_at < $1 OR used_at < $1 OR revoked_at < $1;

-- name: DeleteOldInvites :execrows
DELETE FROM invites WHERE expires_at < $1 OR redeemed_at < $1;

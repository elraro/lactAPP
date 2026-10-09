-- name: InsertAIAnalysis :one
INSERT INTO ai_analyses (id, family_id, baby_id, kind, range_days, locale, provider, model, context_hash, result, created_by, created_at)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
RETURNING *;

-- name: FindAIAnalysis :one
-- An earlier answer to exactly the same question: same context, language and model.
SELECT * FROM ai_analyses
WHERE family_id = $1 AND context_hash = $2 AND locale = $3 AND provider = $4 AND model = $5
ORDER BY created_at DESC
LIMIT 1;

-- name: ListAIAnalyses :many
SELECT * FROM ai_analyses
WHERE family_id = $1 AND baby_id = $2
ORDER BY created_at DESC
LIMIT $3;

-- name: CountAIAnalysesSince :one
SELECT count(*) FROM ai_analyses WHERE family_id = $1 AND created_at >= $2;

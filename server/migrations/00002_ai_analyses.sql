-- +goose Up
CREATE TABLE ai_analyses (
  id            uuid PRIMARY KEY,
  family_id     uuid        NOT NULL REFERENCES families(id),
  baby_id       uuid        NOT NULL,
  kind          text        NOT NULL CHECK (kind IN ('overview','sleep','feeding')),
  range_days    int         NOT NULL CHECK (range_days BETWEEN 1 AND 90),
  locale        text        NOT NULL,
  provider      text        NOT NULL,
  model         text        NOT NULL,
  -- SHA-256 of the exact context document that was sent.
  context_hash  bytea       NOT NULL,
  result        jsonb       NOT NULL,
  created_by    uuid        NOT NULL REFERENCES users(id),
  created_at    timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (baby_id, family_id) REFERENCES babies (id, family_id)
);
CREATE INDEX ai_analyses_recent ON ai_analyses (family_id, baby_id, created_at DESC);
CREATE INDEX ai_analyses_cache  ON ai_analyses (family_id, context_hash);

-- +goose Down
DROP TABLE ai_analyses;

-- +goose Up
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE families (
  id          uuid PRIMARY KEY,
  name        text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  timezone    text        NOT NULL,
  ai_enabled  boolean     NOT NULL DEFAULT false,
  sync_seq    bigint      NOT NULL DEFAULT 1,
  sync_epoch  uuid        NOT NULL,
  seq         bigint      NOT NULL DEFAULT 1,
  version     int         NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id            uuid PRIMARY KEY,
  family_id     uuid        NOT NULL REFERENCES families(id),
  email         citext      NOT NULL UNIQUE,
  display_name  text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 100),
  password_hash text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  disabled_at   timestamptz
);
CREATE INDEX users_family ON users (family_id);

CREATE TABLE devices (
  id           uuid PRIMARY KEY,
  family_id    uuid        NOT NULL REFERENCES families(id),
  user_id      uuid        NOT NULL REFERENCES users(id),
  name         text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  platform     text        NOT NULL CHECK (length(platform) BETWEEN 1 AND 30),
  app_version  text        NOT NULL CHECK (length(app_version) BETWEEN 1 AND 30),
  created_at   timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  revoked_at   timestamptz
);
CREATE INDEX devices_family ON devices (family_id);

CREATE TABLE refresh_tokens (
  id           uuid PRIMARY KEY,
  device_id    uuid        NOT NULL REFERENCES devices(id),
  user_id      uuid        NOT NULL REFERENCES users(id),
  token_hash   bytea       NOT NULL UNIQUE,
  replaced_by  uuid        REFERENCES refresh_tokens(id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  used_at      timestamptz,
  expires_at   timestamptz NOT NULL,
  revoked_at   timestamptz
);
CREATE INDEX refresh_tokens_device ON refresh_tokens (device_id);
CREATE INDEX refresh_tokens_user   ON refresh_tokens (user_id);

CREATE TABLE invites (
  id           uuid PRIMARY KEY,
  family_id    uuid        NOT NULL REFERENCES families(id),
  code_hash    bytea       NOT NULL UNIQUE,
  purpose      text        NOT NULL CHECK (purpose IN ('join_family','set_password')),
  user_id      uuid        REFERENCES users(id),
  created_by   uuid        REFERENCES users(id),
  created_at   timestamptz NOT NULL DEFAULT now(),
  expires_at   timestamptz NOT NULL,
  redeemed_at  timestamptz,
  CHECK ((purpose = 'set_password') = (user_id IS NOT NULL))
);

CREATE TABLE babies (
  id             uuid PRIMARY KEY,
  family_id      uuid        NOT NULL REFERENCES families(id),
  name           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
  date_of_birth  date        NOT NULL,
  sex            text        CHECK (sex IN ('female','male')),
  notes          text        CHECK (length(notes) <= 4000),
  seq            bigint      NOT NULL,
  version        int         NOT NULL DEFAULT 1,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  deleted_at     timestamptz,
  created_by_user_id   uuid NOT NULL REFERENCES users(id),
  created_by_device_id uuid NOT NULL REFERENCES devices(id),
  updated_by_user_id   uuid NOT NULL REFERENCES users(id),
  updated_by_device_id uuid NOT NULL REFERENCES devices(id),
  UNIQUE (id, family_id)
);
CREATE INDEX babies_feed ON babies (family_id, seq);

CREATE TABLE events (
  id          uuid PRIMARY KEY,
  family_id   uuid        NOT NULL REFERENCES families(id),
  baby_id     uuid        NOT NULL,
  type        text        NOT NULL CHECK (type IN ('bottle','diaper','sleep','measurement','note')),
  started_at  timestamptz NOT NULL,
  ended_at    timestamptz,
  timezone    text        NOT NULL,

  amount_ml   int         CHECK (amount_ml BETWEEN 1 AND 1000),
  milk_kind   text        CHECK (milk_kind IN ('formula','breast_milk','mixed')),
  diaper_kind text        CHECK (diaper_kind IN ('wet','dirty','both')),
  sleep_kind  text        CHECK (sleep_kind IN ('nap','night')),
  weight_g    int         CHECK (weight_g  BETWEEN 200 AND 60000),
  height_mm   int         CHECK (height_mm BETWEEN 200 AND 2000),
  head_mm     int         CHECK (head_mm   BETWEEN 100 AND 800),

  notes       text        CHECK (length(notes) <= 4000),

  seq         bigint      NOT NULL,
  version     int         NOT NULL DEFAULT 1,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  deleted_at  timestamptz,
  created_by_user_id   uuid NOT NULL REFERENCES users(id),
  created_by_device_id uuid NOT NULL REFERENCES devices(id),
  updated_by_user_id   uuid NOT NULL REFERENCES users(id),
  updated_by_device_id uuid NOT NULL REFERENCES devices(id),

  FOREIGN KEY (baby_id, family_id) REFERENCES babies (id, family_id),
  CHECK (ended_at IS NULL OR ended_at >= started_at),
  CHECK (
    (type = 'bottle'      AND amount_ml IS NOT NULL
        AND num_nonnulls(diaper_kind, sleep_kind, weight_g, height_mm, head_mm) = 0) OR
    (type = 'diaper'      AND diaper_kind IS NOT NULL AND ended_at IS NULL
        AND num_nonnulls(amount_ml, milk_kind, sleep_kind, weight_g, height_mm, head_mm) = 0) OR
    (type = 'sleep'       AND sleep_kind IS NOT NULL
        AND num_nonnulls(amount_ml, milk_kind, diaper_kind, weight_g, height_mm, head_mm) = 0) OR
    (type = 'measurement' AND num_nonnulls(weight_g, height_mm, head_mm) >= 1 AND ended_at IS NULL
        AND num_nonnulls(amount_ml, milk_kind, diaper_kind, sleep_kind) = 0) OR
    (type = 'note'        AND notes IS NOT NULL AND ended_at IS NULL
        AND num_nonnulls(amount_ml, milk_kind, diaper_kind, sleep_kind, weight_g, height_mm, head_mm) = 0)
  )
);
CREATE INDEX events_feed     ON events (family_id, seq);
CREATE INDEX events_timeline ON events (baby_id, started_at DESC, id DESC);

CREATE TABLE applied_mutations (
  family_id      uuid        NOT NULL REFERENCES families(id),
  mutation_id    uuid        NOT NULL,
  device_id      uuid        NOT NULL REFERENCES devices(id),
  entity         text        NOT NULL,
  entity_id      uuid        NOT NULL,
  status         text        NOT NULL CHECK (status IN ('applied','rejected')),
  error_code     text,
  merged         boolean     NOT NULL DEFAULT false,
  applied_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (family_id, mutation_id)
);

-- +goose Down
DROP TABLE applied_mutations;
DROP TABLE events;
DROP TABLE babies;
DROP TABLE invites;
DROP TABLE refresh_tokens;
DROP TABLE devices;
DROP TABLE users;
DROP TABLE families;

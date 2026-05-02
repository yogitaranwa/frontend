-- 001_create_users.up.sql
-- Creates the users table with PII column annotations.
-- Run with: golang-migrate -path migrations -database $POSTGRES_URL up

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  google_sub  TEXT        NOT NULL UNIQUE,    -- AES-256-GCM encrypted Google subject identifier
  email       TEXT        NOT NULL,            -- AES-256-GCM encrypted email address
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deleted_at  TIMESTAMPTZ                      -- soft delete; NULL = active
);

-- Partial unique index: only one active row per Google subject.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_google_sub
  ON users (google_sub)
  WHERE deleted_at IS NULL;

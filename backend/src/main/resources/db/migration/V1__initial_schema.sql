-- ============================================================
--  EcoTrace — initial schema
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS citext;

-- ---------- Enums ------------------------------------------------
CREATE TYPE user_status         AS ENUM ('ACTIVE','SUSPENDED','BANNED','DELETED');
CREATE TYPE auth_provider       AS ENUM ('GOOGLE','APPLE','EMAIL');
CREATE TYPE device_platform     AS ENUM ('IOS','ANDROID','WEB');
CREATE TYPE waste_volume        AS ENUM ('SMALL','MEDIUM','LARGE');
CREATE TYPE waste_point_status  AS ENUM (
  'PENDING_REVIEW','ACTIVE','CLAIMED','COLLECTED','VERIFIED','REJECTED','ARCHIVED'
);
CREATE TYPE collection_status   AS ENUM (
  'CLAIMED','SUBMITTED','VERIFIED','REJECTED','EXPIRED'
);
CREATE TYPE photo_kind          AS ENUM ('REPORT','REFERENCE','BEFORE','AFTER');
CREATE TYPE points_reason       AS ENUM (
  'REPORT','COLLECTION','BONUS','ACHIEVEMENT','REVERSAL','ADJUSTMENT'
);
CREATE TYPE moderation_target   AS ENUM ('WASTE_POINT','COLLECTION','USER');
CREATE TYPE moderation_status   AS ENUM ('OPEN','IN_REVIEW','RESOLVED','DISMISSED');

-- ---------- users ------------------------------------------------
CREATE TABLE users (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  email           CITEXT       NOT NULL UNIQUE,
  display_name    VARCHAR(80)  NOT NULL,
  avatar_url      TEXT,
  bio             TEXT,
  total_points    BIGINT       NOT NULL DEFAULT 0 CHECK (total_points >= 0),
  level           INT          NOT NULL DEFAULT 1,
  locale          VARCHAR(10)  NOT NULL DEFAULT 'en',
  timezone        VARCHAR(50),
  status          user_status  NOT NULL DEFAULT 'ACTIVE',
  version         BIGINT       NOT NULL DEFAULT 0,
  last_active_at  TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  deleted_at      TIMESTAMPTZ
);
CREATE INDEX idx_users_active_points
  ON users (total_points DESC)
  WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- ---------- user_auth_providers ----------------------------------
CREATE TABLE user_auth_providers (
  id                 UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id            UUID           NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider           auth_provider  NOT NULL,
  provider_user_id   TEXT           NOT NULL,
  email_at_provider  CITEXT,
  created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
  UNIQUE (provider, provider_user_id)
);
CREATE INDEX idx_uap_user ON user_auth_providers (user_id);

-- ---------- user_devices -----------------------------------------
CREATE TABLE user_devices (
  id            UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform      device_platform  NOT NULL,
  push_token    TEXT             NOT NULL,
  app_version   VARCHAR(32),
  last_seen_at  TIMESTAMPTZ      NOT NULL DEFAULT now(),
  created_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
  UNIQUE (platform, push_token)
);
CREATE INDEX idx_devices_user ON user_devices (user_id);

-- ---------- waste_categories -------------------------------------
CREATE TABLE waste_categories (
  id                 SMALLSERIAL  PRIMARY KEY,
  code               VARCHAR(32)  NOT NULL UNIQUE,
  display_name       VARCHAR(64)  NOT NULL,
  icon_url           TEXT,
  points_multiplier  NUMERIC(4,2) NOT NULL DEFAULT 1.00,
  is_hazardous       BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------- waste_points -----------------------------------------
CREATE TABLE waste_points (
  id                      UUID                   PRIMARY KEY DEFAULT gen_random_uuid(),
  reported_by_user_id     UUID                   NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  category_id             SMALLINT               NOT NULL REFERENCES waste_categories(id),
  location                GEOGRAPHY(POINT, 4326) NOT NULL,
  location_geohash        VARCHAR(12)            NOT NULL,
  address_text            TEXT,
  region_code             VARCHAR(16),
  estimated_volume        waste_volume           NOT NULL DEFAULT 'SMALL',
  description             TEXT,
  status                  waste_point_status     NOT NULL DEFAULT 'PENDING_REVIEW',
  claimed_by_user_id      UUID                   REFERENCES users(id) ON DELETE SET NULL,
  claimed_at              TIMESTAMPTZ,
  claim_expires_at        TIMESTAMPTZ,
  verified_collection_id  UUID,
  reports_count           INT                    NOT NULL DEFAULT 0,
  version                 BIGINT                 NOT NULL DEFAULT 0,
  created_at              TIMESTAMPTZ            NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ            NOT NULL DEFAULT now(),
  deleted_at              TIMESTAMPTZ,
  CONSTRAINT chk_no_self_claim
    CHECK (claimed_by_user_id IS NULL OR claimed_by_user_id <> reported_by_user_id)
);
CREATE INDEX idx_wp_location  ON waste_points USING GIST (location);
CREATE INDEX idx_wp_geohash   ON waste_points (location_geohash);
CREATE INDEX idx_wp_status    ON waste_points (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_wp_reporter  ON waste_points (reported_by_user_id);
CREATE INDEX idx_wp_claimer   ON waste_points (claimed_by_user_id) WHERE claimed_by_user_id IS NOT NULL;
CREATE INDEX idx_wp_region    ON waste_points (region_code);

-- ---------- waste_point_photos -----------------------------------
CREATE TABLE waste_point_photos (
  id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  waste_point_id       UUID         NOT NULL REFERENCES waste_points(id) ON DELETE CASCADE,
  uploaded_by_user_id  UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  storage_key          TEXT         NOT NULL,
  url                  TEXT         NOT NULL,
  width                INT,
  height               INT,
  bytes                BIGINT,
  kind                 photo_kind   NOT NULL DEFAULT 'REPORT',
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_wpp_pin ON waste_point_photos (waste_point_id);

-- ---------- collections ------------------------------------------
CREATE TABLE collections (
  id                    UUID                   PRIMARY KEY DEFAULT gen_random_uuid(),
  waste_point_id        UUID                   NOT NULL REFERENCES waste_points(id) ON DELETE RESTRICT,
  collector_user_id     UUID                   NOT NULL REFERENCES users(id)        ON DELETE RESTRICT,
  status                collection_status      NOT NULL DEFAULT 'CLAIMED',
  claimed_at            TIMESTAMPTZ            NOT NULL DEFAULT now(),
  submitted_at          TIMESTAMPTZ,
  verified_at           TIMESTAMPTZ,
  rejected_at           TIMESTAMPTZ,
  collector_location    GEOGRAPHY(POINT, 4326),
  distance_from_pin_m   NUMERIC(8,2),
  dwell_seconds         INT,
  notes                 TEXT,
  rejection_reason      TEXT,
  points_awarded        INT                    NOT NULL DEFAULT 0,
  reviewed_by_user_id   UUID                   REFERENCES users(id) ON DELETE SET NULL,
  version               BIGINT                 NOT NULL DEFAULT 0,
  created_at            TIMESTAMPTZ            NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ            NOT NULL DEFAULT now()
);
CREATE INDEX idx_col_pin       ON collections (waste_point_id);
CREATE INDEX idx_col_collector ON collections (collector_user_id, created_at DESC);
CREATE INDEX idx_col_status    ON collections (status);
CREATE UNIQUE INDEX uniq_col_open_per_pin
  ON collections (waste_point_id)
  WHERE status IN ('CLAIMED','SUBMITTED');

ALTER TABLE waste_points
  ADD CONSTRAINT fk_wp_verified_collection
  FOREIGN KEY (verified_collection_id)
  REFERENCES collections(id) ON DELETE SET NULL;

-- ---------- collection_evidence ----------------------------------
CREATE TABLE collection_evidence (
  id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  collection_id  UUID         NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
  storage_key    TEXT         NOT NULL,
  url            TEXT         NOT NULL,
  kind           photo_kind   NOT NULL DEFAULT 'AFTER',
  width          INT,
  height         INT,
  bytes          BIGINT,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ce_collection ON collection_evidence (collection_id);

-- ---------- points_transactions (ledger) -------------------------
CREATE TABLE points_transactions (
  id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID            NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  delta           INT             NOT NULL CHECK (delta <> 0),
  reason          points_reason   NOT NULL,
  collection_id   UUID            REFERENCES collections(id)  ON DELETE SET NULL,
  waste_point_id  UUID            REFERENCES waste_points(id) ON DELETE SET NULL,
  metadata        JSONB           NOT NULL DEFAULT '{}'::JSONB,
  created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX idx_pt_user_time   ON points_transactions (user_id, created_at DESC);
CREATE INDEX idx_pt_collection  ON points_transactions (collection_id);
CREATE UNIQUE INDEX uniq_pt_collection_award
  ON points_transactions (collection_id)
  WHERE reason = 'COLLECTION';

-- ---------- levels (reference) -----------------------------------
CREATE TABLE levels (
  level       INT          PRIMARY KEY,
  name        VARCHAR(64)  NOT NULL,
  min_points  INT          NOT NULL UNIQUE,
  icon_url    TEXT
);

-- ---------- achievements -----------------------------------------
CREATE TABLE achievements (
  id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  code           VARCHAR(64)  NOT NULL UNIQUE,
  name           VARCHAR(128) NOT NULL,
  description    TEXT,
  icon_url       TEXT,
  criteria       JSONB        NOT NULL,
  points_reward  INT          NOT NULL DEFAULT 0,
  is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_achievements (
  user_id         UUID         NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
  achievement_id  UUID         NOT NULL REFERENCES achievements(id) ON DELETE CASCADE,
  unlocked_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, achievement_id)
);

-- ---------- moderation_reports -----------------------------------
CREATE TABLE moderation_reports (
  id                   UUID               PRIMARY KEY DEFAULT gen_random_uuid(),
  target_type          moderation_target  NOT NULL,
  target_id            UUID               NOT NULL,
  reporter_user_id     UUID               REFERENCES users(id) ON DELETE SET NULL,
  reason               VARCHAR(64)        NOT NULL,
  description          TEXT,
  status               moderation_status  NOT NULL DEFAULT 'OPEN',
  resolution_action    VARCHAR(64),
  reviewed_by_user_id  UUID               REFERENCES users(id) ON DELETE SET NULL,
  resolved_at          TIMESTAMPTZ,
  created_at           TIMESTAMPTZ        NOT NULL DEFAULT now()
);
CREATE INDEX idx_mod_target ON moderation_reports (target_type, target_id);
CREATE INDEX idx_mod_open
  ON moderation_reports (created_at)
  WHERE status IN ('OPEN','IN_REVIEW');

-- ---------- notifications ----------------------------------------
CREATE TABLE notifications (
  id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type        VARCHAR(64)  NOT NULL,
  title       VARCHAR(160) NOT NULL,
  body        TEXT,
  payload     JSONB        NOT NULL DEFAULT '{}'::JSONB,
  read_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user_unread
  ON notifications (user_id, created_at DESC)
  WHERE read_at IS NULL;

-- ---------- updated_at trigger -----------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated  BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_uap_updated    BEFORE UPDATE ON user_auth_providers
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_wp_updated     BEFORE UPDATE ON waste_points
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_col_updated    BEFORE UPDATE ON collections
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

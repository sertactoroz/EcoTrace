# Step 2 — Database Design (PostgreSQL)

## Design principles

- **UUID primary keys** everywhere (except small reference tables) — safe for distributed systems, no ID enumeration, easy to merge across regions later.
- **`TIMESTAMPTZ` only** — never store local time.
- **PostGIS `GEOGRAPHY(POINT, 4326)`** for locations — meters-based distance math out of the box.
- **Native `ENUM` types** for status fields — type safety + small storage. (Trade-off: adding values requires `ALTER TYPE`; switch to `VARCHAR` + `CHECK` if values churn.)
- **Soft delete** (`deleted_at`) only on user-facing entities.
- **Append-only ledger** for points (`points_transactions`); `users.total_points` is a denormalized projection updated in the same transaction.
- **Lookup tables** (`waste_categories`, `levels`, `achievements`) for things ops/business will tune without code changes.
- **Audit fields** on every mutable table (`created_at`, `updated_at`).
- **Indexes** designed around the three hot paths: spatial queries, leaderboard reads, "my activity" feeds.

## Entity map

```
                         ┌───────────────┐
                         │     users     │◄─────────┐
                         └───┬─────┬─────┘          │
                             │     │                │
       ┌─────────────────────┘     └─────────┐      │
       │                                     │      │
       ▼                                     ▼      │
┌─────────────────────┐              ┌──────────────┴─────┐
│ user_auth_providers │              │   user_devices     │
└─────────────────────┘              └────────────────────┘

           ┌──────────────────┐                  ┌──────────────────┐
           │ waste_categories │◄──┐         ┌───►│      levels      │
           └──────────────────┘   │         │    └──────────────────┘
                                  │         │
                          ┌───────┴─────────┴───────┐
                          │      waste_points       │◄──┐
                          └────┬───────────────┬────┘   │
                               │               │        │
                               ▼               ▼        │
                  ┌──────────────────────┐  ┌───────────┴────────────┐
                  │ waste_point_photos   │  │       collections      │
                  └──────────────────────┘  └────┬──────────────┬────┘
                                                 │              │
                                                 ▼              ▼
                                  ┌──────────────────────┐  ┌──────────────────────┐
                                  │ collection_evidence  │  │ points_transactions  │
                                  └──────────────────────┘  └──────────────────────┘

       ┌────────────────────┐    ┌──────────────────┐    ┌─────────────────┐
       │ moderation_reports │    │   achievements   │◄──►│ user_achievements│
       └────────────────────┘    └──────────────────┘    └─────────────────┘

                                 ┌──────────────────┐
                                 │  notifications   │
                                 └──────────────────┘
```

## Entities & relationships

### `users`
The user identity record. Total points and level are denormalized for fast leaderboard reads — `points_transactions` is the source of truth.

### `user_auth_providers`
External identities (Google, later Apple/email). Many-to-one to `users`. A single user can have multiple providers linked, but each `(provider, provider_user_id)` pair is globally unique.

### `user_devices`
Push tokens for FCM/APNs. Many-to-one to `users`. Tokens unique per platform.

### `waste_categories`
Reference data: `PLASTIC`, `GLASS`, `EWASTE`, `HAZARDOUS`, etc. `points_multiplier` lets you reward harder/dirtier work more.

### `waste_points`
**The spatial heart.** A waste pin reported by a user, lifecycle-managed via `status`.

- Many-to-one to `users` (`reported_by_user_id`) — who reported it.
- Many-to-one to `users` (`claimed_by_user_id`) — currently claiming user, nullable. A `CHECK` prevents claiming your own report.
- Many-to-one to `waste_categories`.
- One-to-many to `waste_point_photos`.
- One-to-many to `collections` (a pin can be claimed, abandoned, re-claimed). `verified_collection_id` points to the *successful* collection that closed it.
- Indexed by `GIST` on `location` (spatial) and BTREE on `location_geohash` for tile cache keys.

### `waste_point_photos`
Photos attached to the report. Many-to-one to `waste_points` (CASCADE delete). Storage key + CDN URL — actual bytes live in S3/GCS.

### `collections`
A user's attempt to physically collect a pin. State machine: `CLAIMED → SUBMITTED → VERIFIED | REJECTED | EXPIRED`.

- Many-to-one to `waste_points`.
- Many-to-one to `users` (`collector_user_id`).
- One-to-many to `collection_evidence` — the after-photos.
- A **partial unique index** enforces "only one open collection per pin at a time" (status ∈ {`CLAIMED`,`SUBMITTED`}).
- Captures anti-fraud signals: `collector_location`, `distance_from_pin_m`, `dwell_seconds`.

### `collection_evidence`
After-photos and any other artifacts proving the collection. Many-to-one to `collections` (CASCADE delete).

### `points_transactions`
**Append-only ledger.** Every reward, bonus, or reversal is a row. Negative deltas allowed (for reversals). Reason codes link back to the originating `collection_id` or `waste_point_id`. Sum per user equals `users.total_points`.

### `levels`, `achievements`, `user_achievements`
Gamification reference data + a junction table.

### `moderation_reports`
Generic flagging table — `(target_type, target_id)` polymorphic pointer to `waste_points`, `collections`, or `users`.

### `notifications`
In-app inbox. Push delivery is separate.

## Relationship summary

| Relationship | Type | Cascade |
|---|---|---|
| `users` → `user_auth_providers` | 1 : N | CASCADE |
| `users` → `user_devices` | 1 : N | CASCADE |
| `users` → `waste_points` (reporter) | 1 : N | RESTRICT (preserve history) |
| `users` → `waste_points` (claimer) | 1 : N | SET NULL |
| `waste_categories` → `waste_points` | 1 : N | RESTRICT |
| `waste_points` → `waste_point_photos` | 1 : N | CASCADE |
| `waste_points` ↔ `collections` | 1 : N + back-ref | RESTRICT (audit trail) |
| `users` → `collections` (collector) | 1 : N | RESTRICT |
| `collections` → `collection_evidence` | 1 : N | CASCADE |
| `users` ↔ `achievements` | M : N via `user_achievements` | CASCADE |
| `users` → `points_transactions` | 1 : N | RESTRICT |

## SQL schema

```sql
-- ============================================================
--  EcoTrace — initial schema  (PostgreSQL 15+ recommended)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS postgis;    -- spatial types
CREATE EXTENSION IF NOT EXISTS citext;     -- case-insensitive text

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

-- ---------- waste_categories (seeded) ----------------------------
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
  verified_collection_id  UUID,                  -- FK added after collections table
  reports_count           INT                    NOT NULL DEFAULT 0,
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
```

## Notes & trade-offs

1. **`users.total_points` is denormalized.** Every insert into `points_transactions` must update `users.total_points` in the **same transaction** (in the service layer, not via trigger — triggers make magic invisible). The ledger is the source of truth; if they diverge, recompute from the ledger.

2. **Leaderboards live in Redis, not Postgres.** The `idx_users_active_points` index is for admin queries and Redis warm-up only. Don't run "top 100" SQL queries on a hot path.

3. **`waste_points.location` is `GEOGRAPHY`, not `GEOMETRY`.** Slightly slower but uses meters natively (`ST_DWithin(loc, point, 500)` = "within 500m").

4. **`location_geohash` is denormalized** so the cache layer can build keys (`tile:gh7:abc1234`) without recomputing geohash on every read.

5. **Polymorphic `moderation_reports`** has no DB-level FK to its target. Keep polymorphic until the moderation product hardens; split into typed tables only when needed.

6. **No partitioning yet.** When `waste_points` or `collections` cross ~50–100M rows, partition by `region_code`. The schema is partition-ready.

7. **Photos table is intentionally split** between `waste_point_photos` and `collection_evidence` — they're attached to different parents and have different lifecycles.

8. **Soft delete is only on `users` and `waste_points`.** Collections and ledger entries are immutable history — never delete.

9. **`achievements.criteria` is JSONB** so the rules engine can evolve without schema migrations.

10. **Schema migration tool**: use **Flyway**. Number this file `V1__initial_schema.sql`.

## Open questions to confirm before treating final

- **Verification model**: if collections require a second user or admin sign-off, may want a `collection_reviews` join table (currently captured by the single `reviewed_by_user_id` field).
- **Multi-tenancy**: if onboarding municipalities/NGOs as B2B partners, need an `organizations` table and a `tenant_id` column on most tables. Easier to add now than later.
- **i18n of `display_name` on lookup tables**: `waste_categories.display_name` is single-locale. If launching in multiple languages, use a `waste_category_translations(category_id, locale, display_name)` pattern.

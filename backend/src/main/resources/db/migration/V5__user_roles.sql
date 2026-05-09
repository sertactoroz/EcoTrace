-- ---------- user_role enum --------------------------------------
CREATE TYPE user_role AS ENUM ('MODERATOR', 'ADMIN');

-- ---------- user_roles -------------------------------------------
-- USER is implicit for every authenticated user; only privileged roles are stored.
CREATE TABLE user_roles (
  id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role        user_role    NOT NULL,
  granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
  granted_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
  UNIQUE (user_id, role)
);
CREATE INDEX idx_user_roles_user ON user_roles (user_id);

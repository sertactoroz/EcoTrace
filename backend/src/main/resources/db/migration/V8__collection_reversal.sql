-- Reversal flow: a verified collection can be undone by an admin.
--
-- collections.status gets a REVERSED terminal state with audit columns,
-- and points_transactions can link a REVERSAL row back to the original
-- positive entry for idempotent recompute.

ALTER TYPE collection_status ADD VALUE IF NOT EXISTS 'REVERSED';

ALTER TABLE collections
  ADD COLUMN reversed_at          TIMESTAMPTZ,
  ADD COLUMN reversed_by_user_id  UUID REFERENCES users(id),
  ADD COLUMN reversal_reason      TEXT;

ALTER TABLE points_transactions
  ADD COLUMN reverses_transaction_id UUID
    REFERENCES points_transactions(id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uniq_pt_reversal_target
  ON points_transactions (reverses_transaction_id)
  WHERE reason = 'REVERSAL';

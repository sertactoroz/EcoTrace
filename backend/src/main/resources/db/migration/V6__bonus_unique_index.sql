-- Idempotency guard for the reporter bonus. At most one BONUS row per collection.
CREATE UNIQUE INDEX uniq_pt_collection_bonus
  ON points_transactions (collection_id)
  WHERE reason = 'BONUS';

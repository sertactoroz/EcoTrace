-- Velocity check on verify: count this collector's submissions in the last hour.
CREATE INDEX idx_col_collector_submitted
  ON collections (collector_user_id, submitted_at DESC)
  WHERE submitted_at IS NOT NULL;

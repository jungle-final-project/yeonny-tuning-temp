CREATE INDEX IF NOT EXISTS idx_price_snapshots_part_source_collected
  ON price_snapshots(part_id, source, collected_at);

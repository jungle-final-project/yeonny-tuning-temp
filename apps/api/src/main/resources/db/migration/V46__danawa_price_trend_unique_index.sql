CREATE UNIQUE INDEX IF NOT EXISTS ux_price_snapshots_danawa_trend_part_source_collected
  ON price_snapshots(part_id, source, collected_at)
  WHERE source = 'DANAWA_PRICE_TREND';

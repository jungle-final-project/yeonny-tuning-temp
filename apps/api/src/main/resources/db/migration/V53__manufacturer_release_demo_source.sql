INSERT INTO manufacturer_sources (
  public_id,
  manufacturer,
  category_scope,
  source_type,
  source_url,
  enabled,
  poll_interval_minutes,
  parser_config,
  status,
  created_at,
  updated_at
)
VALUES (
  '00000000-0000-4000-8000-000000009501',
  'BuildGraph Demo',
  'GPU',
  'RSS',
  'http://localhost:8080/api/demo/manufacturer-release-feed.xml',
  false,
  1440,
  '{
    "demo": true,
    "searchQuery": "ASUS ROG Astral GeForce RTX 5090 OC 32GB",
    "note": "Local demo RSS for manufacturer release intake. Enable or scan manually for presentation."
  }'::jsonb,
  'ACTIVE',
  now(),
  now()
)
ON CONFLICT (source_url) WHERE deleted_at IS NULL
DO UPDATE SET
  manufacturer = EXCLUDED.manufacturer,
  category_scope = EXCLUDED.category_scope,
  source_type = EXCLUDED.source_type,
  enabled = EXCLUDED.enabled,
  poll_interval_minutes = EXCLUDED.poll_interval_minutes,
  parser_config = EXCLUDED.parser_config,
  status = 'ACTIVE',
  error_summary = NULL,
  updated_at = now();

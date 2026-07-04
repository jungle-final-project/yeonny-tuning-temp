UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource',
      CASE
        WHEN attributes->>'catalogGeneration' = 'EXTERNAL_REFRESH' THEN 'NAVER_SHOPPING_SEARCH'
        ELSE coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED')
      END,
      'specConfidence',
      CASE
        WHEN attributes->>'catalogGeneration' = 'EXTERNAL_REFRESH' THEN 'ESTIMATED_FROM_TITLE'
        ELSE coalesce(attributes->>'specConfidence', 'MODEL_LEVEL')
      END
    )),
    updated_at = now()
WHERE status = 'ACTIVE'
  AND deleted_at IS NULL
  AND category IN ('CPU', 'GPU', 'RAM', 'MOTHERBOARD', 'STORAGE', 'PSU', 'CASE', 'COOLER');

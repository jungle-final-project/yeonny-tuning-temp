-- V47 inserted Danawa trend points from generic search-result pcode matching.
-- A later audit found that search-result pcode matching can resolve to laptops,
-- complete PCs, power banks, or adjacent part variants. Keep manually verified
-- pcode backfills and remove unverified search-derived trend points.
DELETE FROM price_snapshots
WHERE source = 'DANAWA_PRICE_TREND'
  AND raw_payload->>'selectorVersion' = 'danawa-price-trend-ajax-v1'
  AND raw_payload->>'resolvedFrom' = 'search'
  AND COALESCE(raw_payload->>'matchValidated', 'false') <> 'true';

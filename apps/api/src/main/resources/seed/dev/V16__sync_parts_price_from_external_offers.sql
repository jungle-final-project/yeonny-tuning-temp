WITH latest_naver_offer AS (
  SELECT DISTINCT ON (peo.part_id)
         peo.part_id,
         peo.low_price,
         peo.raw_payload,
         peo.refreshed_at
  FROM part_external_offers peo
  JOIN parts p ON p.id = peo.part_id
  WHERE peo.source = 'NAVER_SHOPPING_SEARCH'
    AND peo.deleted_at IS NULL
    AND peo.low_price IS NOT NULL
    AND p.deleted_at IS NULL
  ORDER BY peo.part_id, peo.refreshed_at DESC, peo.id DESC
)
UPDATE parts p
SET price = latest_naver_offer.low_price,
    updated_at = now()
FROM latest_naver_offer
WHERE p.id = latest_naver_offer.part_id
  AND p.price <> latest_naver_offer.low_price;

WITH latest_naver_offer AS (
  SELECT DISTINCT ON (peo.part_id)
         peo.part_id,
         peo.low_price,
         peo.raw_payload,
         peo.refreshed_at
  FROM part_external_offers peo
  JOIN parts p ON p.id = peo.part_id
  WHERE peo.source = 'NAVER_SHOPPING_SEARCH'
    AND peo.deleted_at IS NULL
    AND peo.low_price IS NOT NULL
    AND p.deleted_at IS NULL
  ORDER BY peo.part_id, peo.refreshed_at DESC, peo.id DESC
)
INSERT INTO price_snapshots (
  part_id,
  price,
  source,
  collected_at,
  raw_payload
)
SELECT part_id,
       low_price,
       'NAVER_SHOPPING_SEARCH',
       coalesce(refreshed_at, now()),
       raw_payload
FROM latest_naver_offer;

-- V23 accidentally matched the Corsair Vengeance RAM asset to an RGB light enhancement kit.
-- Keep the RAM part, but restore the external offer/current price to the real 48GB DDR5 kit.

WITH corrected_offer (
  public_id,
  search_query,
  title,
  image_url,
  supplier_name,
  offer_url,
  low_price,
  raw_payload
) AS (
  VALUES (
    '00000000-0000-4000-8000-000000013014',
    'Corsair Vengeance RGB DDR5 7200 48GB 2x24',
    '[호환] 커세어 Vengeance RGB DDR5 RAM 48GB(2x24GB) 7200MHz CL36 인텔 XMP iCUE 호환 컴퓨터 메모리 블랙 CMH48G',
    'https://shopping-phinf.pstatic.net/main_5856554/58565548468.jpg',
    'G마켓',
    'https://link.gmarket.co.kr/gate/pcs?item-no=3690012208&sub-id=1003&service-code=10000003&lcd=100000055',
    255000,
    '{"source":"naver-shopping-search","selectedFor":"00000000-0000-4000-8000-000000013014","sourceProductKey":"58565548468","maker":null,"brand":null,"officialSpecUrl":"https://www.corsair.com/us/en/c/memory","selectedAt":"2026-06-30","correctionReason":"Previous offer was a Corsair Vengeance RGB light enhancement kit without memory modules."}'
  )
), matched_part AS (
  SELECT p.id AS part_id, c.*
  FROM corrected_offer c
  JOIN parts p ON p.public_id = c.public_id::uuid
  WHERE p.deleted_at IS NULL
), updated_offer AS (
  UPDATE part_external_offers peo
  SET search_query = m.search_query,
      title = m.title,
      image_url = m.image_url,
      supplier_name = m.supplier_name,
      offer_url = m.offer_url,
      low_price = m.low_price,
      raw_payload = m.raw_payload::jsonb,
      refreshed_at = '2026-06-30T15:00:00+09:00',
      updated_at = now()
  FROM matched_part m
  WHERE peo.part_id = m.part_id
    AND peo.source = 'NAVER_SHOPPING_SEARCH'
    AND peo.deleted_at IS NULL
  RETURNING peo.part_id, peo.low_price, peo.source, peo.raw_payload, peo.refreshed_at
), updated_part AS (
  UPDATE parts p
  SET price = u.low_price,
      updated_at = now()
  FROM updated_offer u
  WHERE p.id = u.part_id
  RETURNING p.id AS part_id, p.price
)
INSERT INTO price_snapshots (part_id, price, source, collected_at, raw_payload)
SELECT u.part_id,
       u.low_price,
       u.source,
       u.refreshed_at,
       u.raw_payload
FROM updated_offer u;

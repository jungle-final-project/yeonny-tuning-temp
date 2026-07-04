WITH parsed_psu AS (
  SELECT
    id,
    coalesce(
      substring(upper(name) from '([0-9]{3,4})\s*W'),
      substring(upper(name) from 'RMX SERIES RM([0-9]{3,4})X'),
      substring(upper(name) from 'RM([0-9]{3,4})E'),
      substring(upper(name) from 'RM([0-9]{3,4})X'),
      substring(upper(name) from 'GX-([0-9]{3,4})'),
      substring(upper(name) from 'A([0-9]{3,4})G'),
      substring(upper(name) from 'SF-([0-9]{3,4})'),
      substring(upper(name) from 'GOLD ([0-9]{3,4})'),
      substring(upper(name) from '([0-9]{3,4})\s*ATX'),
      substring(upper(attributes #>> '{externalSources,naver,keyword}') from '([0-9]{3,4})\s*W')
    ) AS parsed_capacity
  FROM parts
  WHERE category = 'PSU'
    AND status = 'ACTIVE'
    AND deleted_at IS NULL
)
UPDATE parts p
SET attributes = jsonb_strip_nulls(p.attributes || jsonb_build_object(
      'capacityW', parsed_psu.parsed_capacity::integer,
      'depthMm', CASE WHEN parsed_psu.parsed_capacity::integer >= 1200 THEN 180 ELSE 160 END,
      'toolReady', true
    )),
    updated_at = now()
FROM parsed_psu
WHERE p.id = parsed_psu.id
  AND parsed_psu.parsed_capacity IS NOT NULL;

UPDATE parts
SET attributes = jsonb_set(attributes, '{toolReady}', 'false'::jsonb, true),
    updated_at = now()
WHERE category = 'PSU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL
  AND NOT (attributes ? 'capacityW');

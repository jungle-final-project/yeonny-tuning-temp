UPDATE parts
SET status = 'INACTIVE',
    updated_at = now()
WHERE category = 'GPU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL
  AND (
    name ILIKE '%GPU 없음%'
    OR name ILIKE '%그래픽 카드GPU 없음%'
    OR name ILIKE '%피규어%'
    OR name ILIKE '%장식%'
    OR name ILIKE '%모형%'
    OR name ILIKE '%수집 가능한 모델%'
  );

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'tdpW', coalesce(
        nullif(attributes->>'tdpW', '')::integer,
        nullif(attributes->>'wattage', '')::integer,
        CASE
          WHEN upper(name) LIKE '%9950%' THEN CASE WHEN upper(name) LIKE '%X3D%' THEN 120 ELSE 170 END
          WHEN upper(name) LIKE '%9900%' THEN 120
          WHEN upper(name) LIKE '%9800%' THEN 120
          WHEN upper(name) LIKE '%9700%' THEN 65
          WHEN upper(name) LIKE '%9600%' THEN 65
          WHEN upper(name) LIKE '%285K%' THEN 125
          WHEN upper(name) LIKE '%265K%' THEN 125
          WHEN upper(name) LIKE '%245K%' THEN 125
          ELSE NULL
        END
      ),
      'coreCount', coalesce(
        nullif(attributes->>'coreCount', '')::integer,
        CASE
          WHEN upper(name) LIKE '%9950%' THEN 16
          WHEN upper(name) LIKE '%9900%' THEN 12
          WHEN upper(name) LIKE '%9800%' OR upper(name) LIKE '%9700%' THEN 8
          WHEN upper(name) LIKE '%9600%' THEN 6
          WHEN upper(name) LIKE '%285K%' THEN 24
          WHEN upper(name) LIKE '%265K%' THEN 20
          WHEN upper(name) LIKE '%245K%' THEN 14
          ELSE NULL
        END
      ),
      'threadCount', coalesce(
        nullif(attributes->>'threadCount', '')::integer,
        CASE
          WHEN upper(name) LIKE '%9950%' THEN 32
          WHEN upper(name) LIKE '%9900%' THEN 24
          WHEN upper(name) LIKE '%9800%' OR upper(name) LIKE '%9700%' THEN 16
          WHEN upper(name) LIKE '%9600%' THEN 12
          WHEN upper(name) LIKE '%285K%' THEN 24
          WHEN upper(name) LIKE '%265K%' THEN 20
          WHEN upper(name) LIKE '%245K%' THEN 14
          ELSE NULL
        END
      ),
      'socket', coalesce(
        attributes->>'socket',
        CASE
          WHEN upper(name) LIKE '%RYZEN%' OR name ILIKE '%라이젠%' OR upper(manufacturer) LIKE '%AMD%' THEN 'AM5'
          WHEN upper(name) LIKE '%CORE ULTRA%' OR name ILIKE '%코어 울트라%' OR name ILIKE '%인텔%' THEN 'LGA1851'
          ELSE NULL
        END
      ),
      'architecture', coalesce(
        attributes->>'architecture',
        CASE
          WHEN upper(name) LIKE '%RYZEN%' OR name ILIKE '%라이젠%' OR upper(manufacturer) LIKE '%AMD%' THEN 'Zen 5'
          WHEN upper(name) LIKE '%CORE ULTRA%' OR name ILIKE '%코어 울트라%' OR name ILIKE '%인텔%' THEN 'Arrow Lake'
          ELSE NULL
        END
      ),
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'CPU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'architecture', coalesce(attributes->>'architecture', 'Blackwell'),
      'series', coalesce(attributes->>'series', 'GeForce RTX 50'),
      'memoryType', coalesce(attributes->>'memoryType', 'GDDR7'),
      'wattage', coalesce(
        nullif(attributes->>'wattage', '')::integer,
        CASE
          WHEN upper(name) LIKE '%5090%' THEN 575
          WHEN upper(name) LIKE '%5080%' THEN 360
          WHEN upper(name) LIKE '%5070%TI%' OR upper(name) LIKE '%5070TI%' THEN 300
          WHEN upper(name) LIKE '%5070%' THEN 250
          WHEN upper(name) LIKE '%5060%TI%' OR upper(name) LIKE '%5060TI%' THEN 180
          WHEN upper(name) LIKE '%5060%' THEN 145
          WHEN upper(name) LIKE '%5050%' THEN 130
          ELSE NULL
        END
      ),
      'requiredSystemPowerW', coalesce(
        nullif(attributes->>'requiredSystemPowerW', '')::integer,
        CASE
          WHEN upper(name) LIKE '%5090%' THEN 1000
          WHEN upper(name) LIKE '%5080%' THEN 850
          WHEN upper(name) LIKE '%5070%TI%' OR upper(name) LIKE '%5070TI%' THEN 750
          WHEN upper(name) LIKE '%5070%' THEN 650
          WHEN upper(name) LIKE '%5060%TI%' OR upper(name) LIKE '%5060TI%' THEN 600
          WHEN upper(name) LIKE '%5060%' OR upper(name) LIKE '%5050%' THEN 550
          ELSE NULL
        END
      ),
      'vramGb', coalesce(
        nullif(attributes->>'vramGb', '')::integer,
        CASE
          WHEN upper(name) LIKE '%5090%' THEN 32
          WHEN upper(name) LIKE '%5080%' THEN 16
          WHEN upper(name) LIKE '%5070%TI%' OR upper(name) LIKE '%5070TI%' THEN 16
          WHEN upper(name) LIKE '%5070%' THEN 12
          WHEN upper(name) LIKE '%5060%TI%' OR upper(name) LIKE '%5060TI%' THEN CASE WHEN upper(name) LIKE '%16GB%' THEN 16 ELSE 8 END
          WHEN upper(name) LIKE '%5060%' OR upper(name) LIKE '%5050%' THEN 8
          ELSE NULL
        END
      ),
      'lengthMm', coalesce(
        nullif(attributes->>'lengthMm', '')::integer,
        CASE
          WHEN upper(name) LIKE '%SFF%' OR upper(name) LIKE '%DUAL%' OR upper(name) LIKE '%2X%' THEN
            CASE
              WHEN upper(name) LIKE '%5090%' THEN 304
              WHEN upper(name) LIKE '%5080%' THEN 280
              ELSE 250
            END
          WHEN upper(name) LIKE '%ASTRAL%' OR upper(name) LIKE '%SUPRIM%' OR upper(name) LIKE '%MASTER%' THEN
            CASE WHEN upper(name) LIKE '%5090%' THEN 360 ELSE 340 END
          WHEN upper(name) LIKE '%5090%' THEN 340
          WHEN upper(name) LIKE '%5080%' THEN 330
          WHEN upper(name) LIKE '%5070%TI%' OR upper(name) LIKE '%5070TI%' THEN 320
          WHEN upper(name) LIKE '%5070%' THEN 300
          WHEN upper(name) LIKE '%5060%' OR upper(name) LIKE '%5050%' THEN 245
          ELSE NULL
        END
      ),
      'slotWidth', coalesce(
        nullif(attributes->>'slotWidth', '')::numeric,
        CASE
          WHEN upper(name) LIKE '%SFF%' OR upper(name) LIKE '%DUAL%' OR upper(name) LIKE '%2X%' THEN 2.5
          WHEN upper(name) LIKE '%5090%' OR upper(name) LIKE '%ASTRAL%' OR upper(name) LIKE '%SUPRIM%' THEN 3.5
          ELSE 3.0
        END
      ),
      'powerConnector', coalesce(attributes->>'powerConnector', '12V-2x6'),
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'GPU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'memoryType', coalesce(attributes->>'memoryType', 'DDR5'),
      'formFactor', coalesce(
        attributes->>'formFactor',
        CASE
          WHEN upper(name) LIKE '%ITX%' THEN 'MINI_ITX'
          WHEN upper(name) LIKE '%M-ATX%' OR upper(name) LIKE '%MATX%' OR upper(name) LIKE '%M ATX%' THEN 'MATX'
          WHEN upper(name) LIKE '%E-ATX%' OR upper(name) LIKE '%EATX%' THEN 'EATX'
          ELSE 'ATX'
        END
      ),
      'widthMm', CASE
        WHEN coalesce(attributes->>'formFactor', '') = 'MINI_ITX' OR upper(name) LIKE '%ITX%' THEN 170
        WHEN coalesce(attributes->>'formFactor', '') = 'MATX' OR upper(name) LIKE '%M-ATX%' OR upper(name) LIKE '%MATX%' OR upper(name) LIKE '%M ATX%' THEN 244
        ELSE 305
      END,
      'depthMm', CASE
        WHEN coalesce(attributes->>'formFactor', '') = 'MINI_ITX' OR upper(name) LIKE '%ITX%' THEN 170
        WHEN coalesce(attributes->>'formFactor', '') = 'EATX' OR upper(name) LIKE '%E-ATX%' OR upper(name) LIKE '%EATX%' THEN 330
        ELSE 244
      END,
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'MOTHERBOARD'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

WITH parsed_psu AS (
  SELECT id, substring(upper(name) from '([0-9]{3,4})\s*W') AS parsed_capacity
  FROM parts
  WHERE category = 'PSU'
    AND status = 'ACTIVE'
    AND deleted_at IS NULL
)
UPDATE parts p
SET attributes = jsonb_strip_nulls(p.attributes || jsonb_build_object(
      'specSource', coalesce(p.attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(p.attributes->>'specConfidence', 'MODEL_LEVEL'),
      'capacityW', coalesce(
        nullif(p.attributes->>'capacityW', '')::integer,
        CASE WHEN parsed_psu.parsed_capacity IS NOT NULL THEN parsed_psu.parsed_capacity::integer ELSE NULL END
      ),
      'atxSpec', coalesce(
        p.attributes->>'atxSpec',
        CASE
          WHEN upper(p.name) LIKE '%ATX 3.1%' THEN 'ATX 3.1'
          WHEN upper(p.name) LIKE '%ATX 3.0%' OR upper(p.name) LIKE '%PCIE5%' OR upper(p.name) LIKE '%PCI-E 5%' THEN 'ATX 3.0'
          ELSE 'ATX 3.1'
        END
      ),
      'gpuConnector', coalesce(p.attributes->>'gpuConnector', '12V-2x6'),
      'depthMm', CASE
        WHEN coalesce(nullif(p.attributes->>'capacityW', '')::integer, CASE WHEN parsed_psu.parsed_capacity IS NOT NULL THEN parsed_psu.parsed_capacity::integer ELSE NULL END) >= 1200 THEN 180
        ELSE 160
      END,
      'toolReady', true
    )),
    updated_at = now()
FROM parsed_psu
WHERE p.id = parsed_psu.id;

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'formFactor', CASE
        WHEN upper(name) LIKE '%EATX%' OR upper(name) LIKE '%E-ATX%' OR upper(name) LIKE '%XL%' OR upper(name) LIKE '%900%' THEN 'EATX_ATX_MATX_ITX'
        ELSE coalesce(attributes->>'formFactor', 'ATX_MATX_ITX')
      END,
      'airflowFocus', coalesce((attributes->>'airflowFocus')::boolean, upper(name) LIKE '%MESH%' OR upper(name) LIKE '%FLOW%' OR upper(name) LIKE '%AIR%'),
      'maxGpuLengthMm', coalesce(
        nullif(attributes->>'maxGpuLengthMm', '')::integer,
        CASE
          WHEN upper(name) LIKE '%MESHIFY 3 XL%' THEN 512
          WHEN upper(name) LIKE '%MESHIFY 3%' THEN 349
          WHEN upper(name) LIKE '%LANCOOL 217%' THEN 380
          WHEN upper(name) LIKE '%H9 FLOW%' THEN 435
          WHEN upper(name) LIKE '%FRAME 4000D%' THEN 370
          WHEN upper(name) LIKE '%EVOLV X2%' THEN 380
          WHEN upper(name) LIKE '%LIGHT BASE 900%' THEN 495
          ELSE NULL
        END
      ),
      'maxCpuCoolerHeightMm', coalesce(
        nullif(attributes->>'maxCpuCoolerHeightMm', '')::integer,
        CASE
          WHEN upper(name) LIKE '%MESHIFY 3 XL%' THEN 185
          WHEN upper(name) LIKE '%MESHIFY 3%' THEN 180
          WHEN upper(name) LIKE '%LANCOOL 217%' THEN 180
          WHEN upper(name) LIKE '%H9 FLOW%' THEN 165
          WHEN upper(name) LIKE '%FRAME 4000D%' THEN 170
          WHEN upper(name) LIKE '%EVOLV X2%' THEN 170
          WHEN upper(name) LIKE '%LIGHT BASE 900%' THEN 190
          ELSE NULL
        END
      ),
      'radiatorSupportMm', CASE
        WHEN upper(name) LIKE '%MESHIFY 3 XL%' OR upper(name) LIKE '%LIGHT BASE 900%' THEN to_jsonb(ARRAY[120,240,280,360,420])
        WHEN upper(name) LIKE '%MESHIFY 3%' OR upper(name) LIKE '%LANCOOL 217%' OR upper(name) LIKE '%H9 FLOW%' OR upper(name) LIKE '%FRAME 4000D%' OR upper(name) LIKE '%EVOLV X2%' THEN to_jsonb(ARRAY[120,240,280,360])
        ELSE attributes->'radiatorSupportMm'
      END,
      'depthMm', CASE
        WHEN upper(name) LIKE '%MESHIFY 3 XL%' THEN 566
        WHEN upper(name) LIKE '%MESHIFY 3%' THEN 468
        WHEN upper(name) LIKE '%LANCOOL 217%' THEN 482
        WHEN upper(name) LIKE '%H9 FLOW%' THEN 466
        WHEN upper(name) LIKE '%FRAME 4000D%' THEN 486
        WHEN upper(name) LIKE '%EVOLV X2%' THEN 490
        WHEN upper(name) LIKE '%LIGHT BASE 900%' THEN 532
        ELSE nullif(attributes->>'depthMm', '')::integer
      END,
      'widthMm', CASE
        WHEN upper(name) LIKE '%MESHIFY 3 XL%' THEN 245
        WHEN upper(name) LIKE '%MESHIFY 3%' THEN 229
        WHEN upper(name) LIKE '%LANCOOL 217%' THEN 238
        WHEN upper(name) LIKE '%H9 FLOW%' THEN 290
        WHEN upper(name) LIKE '%FRAME 4000D%' THEN 239
        WHEN upper(name) LIKE '%EVOLV X2%' THEN 230
        WHEN upper(name) LIKE '%LIGHT BASE 900%' THEN 327
        ELSE nullif(attributes->>'widthMm', '')::integer
      END,
      'heightMm', CASE
        WHEN upper(name) LIKE '%MESHIFY 3 XL%' THEN 520
        WHEN upper(name) LIKE '%MESHIFY 3%' THEN 474
        WHEN upper(name) LIKE '%LANCOOL 217%' THEN 503
        WHEN upper(name) LIKE '%H9 FLOW%' THEN 495
        WHEN upper(name) LIKE '%FRAME 4000D%' THEN 486
        WHEN upper(name) LIKE '%EVOLV X2%' THEN 500
        WHEN upper(name) LIKE '%LIGHT BASE 900%' THEN 484
        ELSE nullif(attributes->>'heightMm', '')::integer
      END,
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'CASE'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'socketSupport', coalesce(attributes->'socketSupport', to_jsonb(ARRAY['AM5','LGA1851','LGA1700'])),
      'coolerType', CASE
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN 'LIQUID_AIO'
        ELSE 'AIR'
      END,
      'radiatorSizeMm', CASE
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN 360
        ELSE nullif(attributes->>'radiatorSizeMm', '')::integer
      END,
      'coolerHeightMm', CASE
        WHEN upper(name) LIKE '%NH-D15%' THEN 168
        WHEN upper(name) LIKE '%ASSASSIN%' THEN 164
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN nullif(attributes->>'coolerHeightMm', '')::integer
        ELSE coalesce(nullif(attributes->>'coolerHeightMm', '')::integer, 160)
      END,
      'tdpW', CASE
        WHEN upper(name) LIKE '%ASSASSIN%' THEN 280
        WHEN upper(name) LIKE '%NH-D15%' THEN 250
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN 280
        ELSE coalesce(nullif(attributes->>'tdpW', '')::integer, 200)
      END,
      'depthMm', CASE
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN 397
        WHEN upper(name) LIKE '%NH-D15%' THEN 150
        ELSE 144
      END,
      'widthMm', CASE
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN 120
        WHEN upper(name) LIKE '%NH-D15%' THEN 161
        ELSE 147
      END,
      'heightMm', CASE
        WHEN upper(name) LIKE '%360%' OR upper(name) LIKE '%LIQUID%' OR upper(name) LIKE '%KRAKEN%' OR upper(name) LIKE '%ICUE%' OR upper(name) LIKE '%ARCTIC%' THEN 27
        WHEN upper(name) LIKE '%NH-D15%' THEN 168
        WHEN upper(name) LIKE '%ASSASSIN%' THEN 164
        ELSE 160
      END,
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'COOLER'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'memoryType', coalesce(attributes->>'memoryType', 'DDR5'),
      'moduleCount', coalesce(nullif(attributes->>'moduleCount', '')::integer, 2),
      'formFactor', coalesce(attributes->>'formFactor', 'UDIMM'),
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'RAM'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

UPDATE parts
SET attributes = jsonb_strip_nulls(attributes || jsonb_build_object(
      'specSource', coalesce(attributes->>'specSource', 'SEED_OR_TITLE_ENRICHED'),
      'specConfidence', coalesce(attributes->>'specConfidence', 'MODEL_LEVEL'),
      'interface', coalesce(attributes->>'interface', CASE WHEN upper(name) LIKE '%PCIE 5%' OR upper(name) LIKE '%GEN5%' OR upper(name) LIKE '%G5%' THEN 'PCIe 5.0 x4 NVMe' ELSE 'M.2 NVMe' END),
      'capacityGb', coalesce(nullif(attributes->>'capacityGb', '')::integer, CASE WHEN upper(name) LIKE '%4TB%' THEN 4000 WHEN upper(name) LIKE '%1TB%' THEN 1000 ELSE 2000 END),
      'formFactor', coalesce(attributes->>'formFactor', 'M.2 2280'),
      'toolReady', true
    )),
    updated_at = now()
WHERE category = 'STORAGE'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL;

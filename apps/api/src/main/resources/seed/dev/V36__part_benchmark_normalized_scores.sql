-- Public-evidence benchmark and official-spec fit scores for every active part.
-- Scores are category-local normalized signals for recommendation/tool reasoning.
-- They are not FPS, render-time, or purchase outcome guarantees.

WITH active_parts AS (
  SELECT id,
         public_id::text AS public_id,
         category,
         name,
         manufacturer,
         price,
         attributes,
         lower(name) AS lower_name
  FROM parts
  WHERE status = 'ACTIVE'
    AND deleted_at IS NULL
), features AS (
  SELECT *,
         CASE WHEN attributes->>'coreCount' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'coreCount')::numeric END AS core_count,
         CASE WHEN attributes->>'threadCount' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'threadCount')::numeric END AS thread_count,
         CASE WHEN attributes->>'tdpW' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'tdpW')::numeric END AS tdp_w,
         CASE WHEN attributes->>'vramGb' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'vramGb')::numeric END AS vram_gb,
         CASE WHEN attributes->>'wattage' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'wattage')::numeric END AS wattage_w,
         CASE WHEN attributes->>'capacityGb' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'capacityGb')::numeric END AS capacity_gb,
         CASE WHEN attributes->>'speedMhz' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'speedMhz')::numeric END AS speed_mhz,
         CASE WHEN attributes->>'readMbps' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'readMbps')::numeric END AS read_mbps,
         CASE WHEN attributes->>'writeMbps' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'writeMbps')::numeric END AS write_mbps,
         CASE WHEN attributes->>'capacityW' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'capacityW')::numeric END AS capacity_w,
         CASE WHEN attributes->>'pcieGeneration' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'pcieGeneration')::numeric END AS pcie_generation,
         CASE WHEN attributes->>'maxGpuLengthMm' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'maxGpuLengthMm')::numeric END AS max_gpu_length_mm,
         CASE WHEN attributes->>'maxCpuCoolerHeightMm' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'maxCpuCoolerHeightMm')::numeric END AS max_cpu_cooler_height_mm,
         CASE WHEN attributes->>'heightMm' ~ '^[0-9]+([.][0-9]+)?$' THEN (attributes->>'heightMm')::numeric END AS height_mm
  FROM active_parts
), scored AS (
  SELECT *,
         CASE category
           WHEN 'CPU' THEN round(LEAST(100::numeric, GREATEST(45::numeric,
             42
             + COALESCE(core_count, 6) * 1.7
             + COALESCE(thread_count, COALESCE(core_count, 6) * 2) * 0.55
             + CASE WHEN lower_name ~ 'x3d' THEN 8 ELSE 0 END
             + CASE WHEN attributes->>'architecture' ~* 'Zen 5|Arrow Lake' THEN 8 ELSE 4 END
             + CASE WHEN lower_name ~ '9950|285k|ultra9|라이젠9' THEN 6
                    WHEN lower_name ~ '9900|265k|ultra7|라이젠7' THEN 4
                    WHEN lower_name ~ '245k|ultra5|라이젠5' THEN 1
                    ELSE 0 END
           )), 2)
           WHEN 'GPU' THEN round(LEAST(100::numeric, GREATEST(45::numeric,
             CASE
               WHEN lower_name ~ '5090' THEN 98
               WHEN lower_name ~ '5080' THEN 91
               WHEN lower_name ~ '5070[ -]?ti' THEN 84
               WHEN lower_name ~ '5070' THEN 78
               WHEN lower_name ~ '5060[ -]?ti' THEN 68
               WHEN lower_name ~ '5060' THEN 60
               ELSE 55
             END
             + LEAST(COALESCE(vram_gb, 8) / 32 * 4, 4)
             + CASE WHEN attributes->>'memoryType' ~* 'GDDR7' THEN 2 ELSE 0 END
           )), 2)
           WHEN 'RAM' THEN round(LEAST(100::numeric, GREATEST(40::numeric,
             38
             + LEAST(COALESCE(capacity_gb, 16) / 128 * 35, 35)
             + LEAST(GREATEST(COALESCE(speed_mhz, 5600) - 4800, 0) / 3200 * 20, 20)
             + CASE WHEN attributes->>'memoryType' ~* 'DDR5' THEN 5 ELSE 0 END
             + CASE WHEN lower(COALESCE(attributes->>'xmp', 'false')) = 'true'
                         OR lower(COALESCE(attributes->>'expo', 'false')) = 'true' THEN 2 ELSE 0 END
           )), 2)
           WHEN 'STORAGE' THEN round(LEAST(100::numeric, GREATEST(40::numeric,
             35
             + LEAST(COALESCE(read_mbps, 5000) / 14900 * 30, 30)
             + LEAST(COALESCE(write_mbps, 4500) / 14000 * 25, 25)
             + LEAST(COALESCE(capacity_gb, 1000) / 4000 * 8, 8)
             + CASE WHEN attributes->>'generation' ~* 'PCIe 5' THEN 2 ELSE 0 END
           )), 2)
           WHEN 'MOTHERBOARD' THEN round(LEAST(100::numeric, GREATEST(45::numeric,
             45
             + CASE
                 WHEN attributes->>'chipset' ~* 'X870|Z890' THEN 26
                 WHEN attributes->>'chipset' ~* 'B850|B860' THEN 20
                 WHEN attributes->>'chipset' ~* 'B650|B760' THEN 14
                 ELSE 10
               END
             + CASE WHEN COALESCE(pcie_generation, 4) >= 5 THEN 10 ELSE 5 END
             + CASE WHEN lower(COALESCE(attributes->>'hasWifi', 'false')) = 'true' THEN 5 ELSE 0 END
             + CASE WHEN attributes->>'memoryType' ~* 'DDR5' THEN 6 ELSE 0 END
             + CASE WHEN attributes->>'formFactor' ~* 'ATX' THEN 4 ELSE 2 END
           )), 2)
           WHEN 'PSU' THEN round(LEAST(100::numeric, GREATEST(35::numeric,
             30
             + LEAST(COALESCE(capacity_w, wattage_w, 500) / 1500 * 35, 35)
             + CASE
                 WHEN attributes->>'efficiency' ~* 'TITANIUM|PLATINUM' THEN 20
                 WHEN attributes->>'efficiency' ~* 'GOLD' THEN 17
                 WHEN attributes->>'efficiency' ~* 'BRONZE' THEN 10
                 WHEN attributes->>'efficiency' ~* '83PLUS' THEN 6
                 ELSE 3
               END
             + CASE WHEN attributes->>'atxSpec' ~* '3.1|3.0' THEN 8 ELSE 0 END
             + CASE WHEN attributes->>'gpuConnector' ~* '12V|PCIe ?5' THEN 5 ELSE 0 END
             + CASE WHEN lower(COALESCE(attributes->>'modular', 'false')) = 'true' THEN 2 ELSE 0 END
           )), 2)
           WHEN 'CASE' THEN round(LEAST(100::numeric, GREATEST(40::numeric,
             35
             + LEAST(COALESCE(max_gpu_length_mm, 330) / 420 * 25, 25)
             + LEAST(COALESCE(max_cpu_cooler_height_mm, 165) / 190 * 15, 15)
             + CASE WHEN lower_name ~ 'airflow|flow|mesh|lancool|north|base' THEN 14 ELSE 8 END
             + CASE WHEN lower_name ~ '360|420|xl|dual' THEN 6 ELSE 2 END
             + CASE WHEN attributes->>'formFactor' ~* 'ATX|E-ATX' THEN 5 ELSE 2 END
           )), 2)
           WHEN 'COOLER' THEN round(LEAST(100::numeric, GREATEST(40::numeric,
             CASE
               WHEN lower_name ~ '420' THEN 96
               WHEN lower_name ~ '360' THEN 91
               WHEN lower_name ~ '280' THEN 84
               WHEN lower_name ~ '240' THEN 78
               WHEN lower_name ~ 'nh-d15|assassin|ak620|phantom spirit|dark rock' THEN 86
               WHEN lower_name ~ 'ak400|nh-u12|peerless' THEN 72
               WHEN lower_name ~ 'nh-l9|low profile' THEN 58
               ELSE 66
             END
             + CASE WHEN jsonb_typeof(attributes->'socketSupport') = 'array'
                         AND jsonb_array_length(attributes->'socketSupport') >= 2 THEN 4 ELSE 0 END
           )), 2)
           ELSE 50::numeric
         END AS normalized_score,
         CASE category
           WHEN 'CPU' THEN 'gaming_creator_dev_ai_helper'
           WHEN 'GPU' THEN 'gaming_ai_creator'
           WHEN 'RAM' THEN 'capacity_speed_workload_fit'
           WHEN 'STORAGE' THEN 'storage_throughput_capacity_fit'
           WHEN 'MOTHERBOARD' THEN 'platform_fit'
           WHEN 'PSU' THEN 'power_headroom_fit'
           WHEN 'CASE' THEN 'clearance_airflow_fit'
           WHEN 'COOLER' THEN 'thermal_socket_fit'
           ELSE 'general_fit'
         END AS workload,
         CASE category
           WHEN 'CPU' THEN 'PassMark CPU Benchmark and PugetBench public results policy'
           WHEN 'GPU' THEN 'Tom''s Hardware GPU hierarchy and UL 3DMark search policy'
           WHEN 'RAM' THEN 'Manufacturer official memory specification'
           WHEN 'STORAGE' THEN 'Manufacturer official SSD specification'
           WHEN 'MOTHERBOARD' THEN 'Manufacturer official motherboard specification'
           WHEN 'PSU' THEN 'Manufacturer official PSU specification'
           WHEN 'CASE' THEN 'Manufacturer official case specification'
           WHEN 'COOLER' THEN 'Manufacturer official cooler specification'
           ELSE 'BuildGraph internal normalized fit policy'
         END AS source_name,
         CASE category
           WHEN 'CPU' THEN 'https://www.cpubenchmark.net/'
           WHEN 'GPU' THEN 'https://www.tomshardware.com/reviews/gpu-hierarchy%2C4388.html'
           ELSE COALESCE(
             attributes->>'specReferenceUrl',
             attributes #>> '{externalSources,naver,officialSpecUrl}',
             'https://search.shopping.naver.com/search/all?query=' || replace(name, ' ', '%20')
           )
         END AS source_url,
         CASE category
           WHEN 'CPU' THEN 'score = clamp(42 + core*1.7 + thread*0.55 + current-architecture bonus + X3D/tier bonus, 45, 100)'
           WHEN 'GPU' THEN 'score = RTX 50-series tier baseline + VRAM/GDDR7 bonus, clamped 45-100'
           WHEN 'RAM' THEN 'score = capacity + DDR5 speed + XMP/EXPO fit, clamped 40-100'
           WHEN 'STORAGE' THEN 'score = sequential read/write + capacity + PCIe generation fit, clamped 40-100'
           WHEN 'MOTHERBOARD' THEN 'score = chipset tier + PCIe generation + Wi-Fi + DDR5/form-factor fit, clamped 45-100'
           WHEN 'PSU' THEN 'score = rated capacity + efficiency + ATX 3.x + modern GPU connector + modular fit, clamped 35-100'
           WHEN 'CASE' THEN 'score = GPU clearance + cooler clearance + airflow/radiator/form-factor fit, clamped 40-100'
           WHEN 'COOLER' THEN 'score = radiator/tower class + socket support breadth, clamped 40-100'
           ELSE 'score = general category fit'
         END AS normalized_formula
  FROM features
)
INSERT INTO benchmark_summaries (
  part_id,
  benchmark_key,
  summary,
  score,
  metadata,
  created_at,
  updated_at
)
SELECT id,
       'normalized-fit-v1:' || public_id,
       category || ' category-local normalized score ' || normalized_score || ' for ' || workload || '. Use as recommendation evidence, not exact FPS or render-time guarantee.',
       normalized_score,
       jsonb_build_object(
         'category', category,
         'workload', workload,
         'sourceName', source_name,
         'sourceUrl', source_url,
         'secondarySourceUrls', CASE
           WHEN category = 'CPU' THEN jsonb_build_array('https://www.pugetsystems.com/pugetbench/results/compare/')
           WHEN category = 'GPU' THEN jsonb_build_array('https://www.3dmark.com/search')
           ELSE jsonb_build_array()
         END,
         'rawScore', jsonb_build_object(
           'coreCount', core_count,
           'threadCount', thread_count,
           'tdpW', tdp_w,
           'vramGb', vram_gb,
           'wattageW', wattage_w,
           'capacityGb', capacity_gb,
           'speedMhz', speed_mhz,
           'readMbps', read_mbps,
           'writeMbps', write_mbps,
           'capacityW', capacity_w,
           'pcieGeneration', pcie_generation,
           'maxGpuLengthMm', max_gpu_length_mm,
           'maxCpuCoolerHeightMm', max_cpu_cooler_height_mm,
           'heightMm', height_mm,
           'categoryTierScore', normalized_score
         ),
         'normalizedFormula', normalized_formula,
         'sourceCheckedAt', '2026-07-01',
         'metadataVersion', 1,
         'scoreScope', 'CATEGORY_LOCAL_ONLY',
         'guaranteePolicy', 'NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE'
       ),
       now(),
       now()
FROM scored
ON CONFLICT (benchmark_key)
DO UPDATE SET
  summary = EXCLUDED.summary,
  score = EXCLUDED.score,
  metadata = EXCLUDED.metadata,
  updated_at = now(),
  deleted_at = NULL;

SELECT setval(pg_get_serial_sequence('benchmark_summaries', 'id'), (SELECT max(id) FROM benchmark_summaries));

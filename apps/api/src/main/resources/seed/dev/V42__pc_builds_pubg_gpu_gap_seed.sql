-- Fill remaining internal PUBG GPU-class FPS evidence gaps with source-verified PC-Builds rows.

WITH fps_seed AS (
  SELECT *
  FROM (VALUES
    ('00000000-0000-4000-8000-000000042001'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', NULL::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 193.00, 164.00, 222.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1dv1sm0jh/core-i5-12400f/geforce-rtx-5060/pubg-battlegrounds/', 'Core i5-12400F', 'GeForce RTX 5060', NULL, 'RTX_5060', 'FPS_CALCULATOR_GPU_CLASS'),
    ('00000000-0000-4000-8000-000000042002'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', NULL::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 130.00, 110.00, 149.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1dv1sm0jh/core-i5-12400f/geforce-rtx-5060/pubg-battlegrounds/', 'Core i5-12400F', 'GeForce RTX 5060', NULL, 'RTX_5060', 'FPS_CALCULATOR_GPU_CLASS'),
    ('00000000-0000-4000-8000-000000042003'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', NULL::uuid, '88622262-a225-456b-b8f1-ae9914d20f70'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 74.00, 63.00, 85.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1dv1sm0jh/core-i5-12400f/geforce-rtx-5060/pubg-battlegrounds/', 'Core i5-12400F', 'GeForce RTX 5060', NULL, 'RTX_5060', 'FPS_CALCULATOR_GPU_CLASS'),
    ('00000000-0000-4000-8000-000000042004'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, 'FHD', 'PC_BUILDS_MEDIUM', 301.00, 256.00, 346.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1wO0jh/ryzen-7-9800x3d/geforce-rtx-5070-ti/pubg-battlegrounds/', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000042005'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, 'QHD', 'PC_BUILDS_MEDIUM', 202.00, 172.00, 233.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1wO0jh/ryzen-7-9800x3d/geforce-rtx-5070-ti/pubg-battlegrounds/', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'FPS_CALCULATOR_EXACT_PARTS'),
    ('00000000-0000-4000-8000-000000042006'::uuid, 'PlayerUnknown''s Battlegrounds', 'pubg', '461c52ef-09bb-4785-8312-a604f85f7fe5'::uuid, '460f7d37-bd23-4bcf-9786-d9c68126a77c'::uuid, 32, '4K', 'PC_BUILDS_MEDIUM', 116.00, 98.00, 133.00, 'PC-Builds FPS calculator', 'https://pc-builds.com/fps-calculator/result/1Ek1wO0jh/ryzen-7-9800x3d/geforce-rtx-5070-ti/pubg-battlegrounds/', 'Ryzen 7 9800X3D', 'GeForce RTX 5070 Ti', 'RYZEN_7_9800X3D', 'RTX_5070_TI', 'FPS_CALCULATOR_EXACT_PARTS')
  ) AS seed(public_id, game_title, game_key, cpu_public_id, gpu_public_id, ram_gb, resolution, graphics_preset, avg_fps, source_min_fps, source_max_fps, source_name, source_url, source_cpu_name, source_gpu_name, cpu_class, gpu_class, source_metric_type)
)
INSERT INTO game_fps_benchmarks (
  public_id,
  game_title,
  game_key,
  cpu_part_id,
  gpu_part_id,
  ram_gb,
  resolution,
  graphics_preset,
  avg_fps,
  one_percent_low_fps,
  source_name,
  source_url,
  source_checked_at,
  confidence,
  metadata,
  created_at,
  updated_at
)
SELECT seed.public_id,
       seed.game_title,
       seed.game_key,
       cpu.id,
       gpu.id,
       seed.ram_gb,
       seed.resolution,
       seed.graphics_preset,
       seed.avg_fps,
       NULL,
       seed.source_name,
       seed.source_url,
       '2026-07-01'::date,
       CASE WHEN seed.source_metric_type = 'FPS_CALCULATOR_EXACT_PARTS' THEN 'MEDIUM' ELSE 'LOW' END,
       jsonb_strip_nulls(jsonb_build_object(
         'aliases', jsonb_build_array('배그', 'pubg', 'battlegrounds', 'playerunknowns battlegrounds'),
         'sourceCpuName', seed.source_cpu_name,
         'sourceGpuName', seed.source_gpu_name,
         'cpuClass', seed.cpu_class,
         'gpuClass', seed.gpu_class,
         'sourceResolutionText', seed.resolution,
         'sourcePresetText', seed.graphics_preset,
         'sourceMinFps', seed.source_min_fps,
         'sourceMaxFps', seed.source_max_fps,
         'sourceMetricType', seed.source_metric_type,
         'sourceAccessMethod', 'MANUAL_PAGE_READ',
         'sourceCapturedText', concat(seed.resolution, ' avg ', seed.avg_fps, ', min ', seed.source_min_fps, ', max ', seed.source_max_fps),
         'driverVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'gameVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'osVersion', 'UNKNOWN_PUBLIC_SOURCE',
         'testScene', 'UNKNOWN_PUBLIC_SOURCE',
         'sampleCount', 'UNKNOWN_PUBLIC_SOURCE',
         'upscaling', 'UNKNOWN_PUBLIC_SOURCE',
         'frameGeneration', 'UNKNOWN_PUBLIC_SOURCE',
         'rayTracing', 'NOT_SPECIFIED',
         'evidenceExactness', seed.source_metric_type,
         'qualityGaps', jsonb_build_array(
           'one_percent_low_fps_not_provided',
           'driver_version_not_provided',
           'game_version_not_provided',
           'test_scene_not_provided'
         ),
         'guaranteePolicy', 'NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE',
         'notes', 'Public FPS reference for recommendation evidence. Do not present as guaranteed FPS.'
       )),
       now(),
       now()
FROM fps_seed seed
LEFT JOIN parts cpu ON seed.cpu_public_id IS NOT NULL AND cpu.public_id = seed.cpu_public_id
JOIN parts gpu ON gpu.public_id = seed.gpu_public_id
ON CONFLICT DO NOTHING;

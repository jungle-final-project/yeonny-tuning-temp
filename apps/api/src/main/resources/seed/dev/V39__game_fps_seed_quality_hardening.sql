-- Harden game FPS evidence so AI/Tool callers can distinguish exact rows from fallback references.

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'chk_game_fps_resolution'
      AND conrelid = 'game_fps_benchmarks'::regclass
  ) THEN
    ALTER TABLE game_fps_benchmarks
      ADD CONSTRAINT chk_game_fps_resolution CHECK (resolution IN ('FHD', 'QHD', '4K'));
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_game_fps_gpu_class
  ON game_fps_benchmarks((metadata->>'gpuClass'))
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_game_fps_cpu_class
  ON game_fps_benchmarks((metadata->>'cpuClass'))
  WHERE deleted_at IS NULL;

UPDATE parts
SET attributes = attributes || jsonb_build_object(
    'gpuClass',
    CASE
      WHEN name ILIKE '%5090%' THEN 'RTX_5090'
      WHEN name ILIKE '%5080%' THEN 'RTX_5080'
      WHEN name ILIKE '%5070 Ti%' OR name ILIKE '%5070Ti%' THEN 'RTX_5070_TI'
      WHEN name ILIKE '%5070%' THEN 'RTX_5070'
      WHEN name ILIKE '%5060 Ti%' OR name ILIKE '%5060Ti%' THEN 'RTX_5060_TI'
      WHEN name ILIKE '%5060%' THEN 'RTX_5060'
    END,
    'hardwareClass',
    CASE
      WHEN name ILIKE '%5090%' THEN 'RTX_5090'
      WHEN name ILIKE '%5080%' THEN 'RTX_5080'
      WHEN name ILIKE '%5070 Ti%' OR name ILIKE '%5070Ti%' THEN 'RTX_5070_TI'
      WHEN name ILIKE '%5070%' THEN 'RTX_5070'
      WHEN name ILIKE '%5060 Ti%' OR name ILIKE '%5060Ti%' THEN 'RTX_5060_TI'
      WHEN name ILIKE '%5060%' THEN 'RTX_5060'
    END
  ),
  updated_at = now()
WHERE category = 'GPU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL
  AND (
    name ILIKE '%5090%'
    OR name ILIKE '%5080%'
    OR name ILIKE '%5070%'
    OR name ILIKE '%5060%'
  );

UPDATE parts
SET attributes = attributes || jsonb_build_object(
    'cpuClass',
    CASE
      WHEN name ILIKE '%9950X3D%' THEN 'RYZEN_9_9950X3D'
      WHEN name ILIKE '%9950X%' THEN 'RYZEN_9_9950X'
      WHEN name ILIKE '%9900X3D%' THEN 'RYZEN_9_9900X3D'
      WHEN name ILIKE '%9900X%' THEN 'RYZEN_9_9900X'
      WHEN name ILIKE '%9800X3D%' THEN 'RYZEN_7_9800X3D'
      WHEN name ILIKE '%9700X%' THEN 'RYZEN_7_9700X'
      WHEN name ILIKE '%9600X%' THEN 'RYZEN_5_9600X'
      WHEN name ILIKE '%285K%' THEN 'INTEL_CORE_ULTRA_9_285K'
      WHEN name ILIKE '%265K%' THEN 'INTEL_CORE_ULTRA_7_265K'
      WHEN name ILIKE '%245K%' THEN 'INTEL_CORE_ULTRA_5_245K'
    END,
    'hardwareClass',
    CASE
      WHEN name ILIKE '%9950X3D%' THEN 'RYZEN_9_9950X3D'
      WHEN name ILIKE '%9950X%' THEN 'RYZEN_9_9950X'
      WHEN name ILIKE '%9900X3D%' THEN 'RYZEN_9_9900X3D'
      WHEN name ILIKE '%9900X%' THEN 'RYZEN_9_9900X'
      WHEN name ILIKE '%9800X3D%' THEN 'RYZEN_7_9800X3D'
      WHEN name ILIKE '%9700X%' THEN 'RYZEN_7_9700X'
      WHEN name ILIKE '%9600X%' THEN 'RYZEN_5_9600X'
      WHEN name ILIKE '%285K%' THEN 'INTEL_CORE_ULTRA_9_285K'
      WHEN name ILIKE '%265K%' THEN 'INTEL_CORE_ULTRA_7_265K'
      WHEN name ILIKE '%245K%' THEN 'INTEL_CORE_ULTRA_5_245K'
    END
  ),
  updated_at = now()
WHERE category = 'CPU'
  AND status = 'ACTIVE'
  AND deleted_at IS NULL
  AND (
    name ILIKE '%9950X3D%'
    OR name ILIKE '%9950X%'
    OR name ILIKE '%9900X3D%'
    OR name ILIKE '%9900X%'
    OR name ILIKE '%9800X3D%'
    OR name ILIKE '%9700X%'
    OR name ILIKE '%9600X%'
    OR name ILIKE '%285K%'
    OR name ILIKE '%265K%'
    OR name ILIKE '%245K%'
  );

UPDATE game_fps_benchmarks
SET metadata = coalesce(metadata, '{}'::jsonb)
    || jsonb_build_object(
      'sourceAccessMethod', 'MANUAL_SEED_FROM_PUBLIC_REFERENCE',
      'sourceCapturedText',
      game_title || ': ' || resolution || ' ' || graphics_preset || ', avg ' || avg_fps::text
        || CASE WHEN one_percent_low_fps IS NULL THEN '' ELSE ', 1% low ' || one_percent_low_fps::text END,
      'driverVersion', coalesce(metadata->>'driverVersion', 'UNKNOWN'),
      'gameVersion', coalesce(metadata->>'gameVersion', 'UNKNOWN'),
      'osVersion', coalesce(metadata->>'osVersion', 'UNKNOWN'),
      'testScene', coalesce(metadata->>'testScene', 'UNKNOWN'),
      'sampleCount', coalesce(metadata->>'sampleCount', 'UNKNOWN'),
      'upscaling', coalesce(metadata->>'upscaling', 'UNKNOWN'),
      'frameGeneration', coalesce(metadata->>'frameGeneration', 'UNKNOWN'),
      'rayTracing',
      CASE
        WHEN graphics_preset ILIKE '%RT%' THEN coalesce(metadata->>'rayTracing', 'ENABLED_OR_PRESET_DEPENDENT')
        ELSE coalesce(metadata->>'rayTracing', 'UNKNOWN')
      END,
      'evidenceExactness',
      CASE
        WHEN metadata->>'hardwareScope' = 'EXACT_PUBLIC_SESSION' THEN 'EXACT_PUBLIC_SESSION'
        ELSE 'PUBLIC_COMPARISON_REFERENCE'
      END,
      'qualityGaps',
      jsonb_build_array(
        'FPS values are public references, not guaranteed service-level predictions',
        'Driver, game patch, scene, and graphics sub-options may be unavailable from the source'
      )
    ),
    updated_at = now()
WHERE deleted_at IS NULL;

-- Public raw benchmark seed for CPU/GPU parts.
-- The existing benchmark_summaries.score remains a category-local normalized score.
-- Raw benchmark values are stored in metadata.rawBenchmarks for evidence/RAG/tool use.

WITH raw_refs AS (
  SELECT *
  FROM (VALUES
    (
      1,
      'GPU',
      '5090',
      NULL,
      'NVIDIA GeForce RTX 5090',
      '3DMark Steel Nomad DX12',
      'UL Solutions 3DMark hardware review',
      'https://benchmarks.ul.com/hardware/gpu/NVIDIA%2BGeForce%2BRTX%2B5090/review',
      'Board partner RTX 5090 cards inherit the public GPU-class benchmark. Exact factory OC, cooling, and power limit can differ.',
      '[{"benchmarkName":"3DMark Steel Nomad DX12 Graphics Score","score":14544,"unit":"score","scoreType":"public hardware-channel reference","higherIsBetter":true}]'::jsonb
    ),
    (
      2,
      'GPU',
      '5080',
      NULL,
      'NVIDIA GeForce RTX 5080',
      '3DMark Steel Nomad DX12',
      'UL Solutions 3DMark hardware review',
      'https://benchmarks.ul.com/hardware/gpu/NVIDIA%2BGeForce%2BRTX%2B5080/review',
      'Board partner RTX 5080 cards inherit the public GPU-class benchmark. Exact factory OC, cooling, and power limit can differ.',
      '[{"benchmarkName":"3DMark Steel Nomad DX12 Graphics Score","score":8858,"unit":"score","scoreType":"public hardware-channel reference","higherIsBetter":true}]'::jsonb
    ),
    (
      3,
      'GPU',
      '5070[ -]?ti',
      NULL,
      'NVIDIA GeForce RTX 5070 Ti',
      '3DMark Steel Nomad DX12',
      'UL Solutions 3DMark hardware review',
      'https://benchmarks.ul.com/hardware/gpu/NVIDIA%2BGeForce%2BRTX%2B5070%2BTi/review',
      'Board partner RTX 5070 Ti cards inherit the public GPU-class benchmark. Exact factory OC, cooling, and power limit can differ.',
      '[{"benchmarkName":"3DMark Steel Nomad DX12 Graphics Score","score":6905,"unit":"score","scoreType":"public hardware-channel reference","higherIsBetter":true}]'::jsonb
    ),
    (
      4,
      'GPU',
      '5070',
      '5070[ -]?ti',
      'NVIDIA GeForce RTX 5070',
      '3DMark Steel Nomad DX12',
      'UL Solutions 3DMark hardware review',
      'https://benchmarks.ul.com/hardware/gpu/NVIDIA%2BGeForce%2BRTX%2B5070/review',
      'Board partner RTX 5070 cards inherit the public GPU-class benchmark. Exact factory OC, cooling, and power limit can differ.',
      '[{"benchmarkName":"3DMark Steel Nomad DX12 Graphics Score","score":5300,"unit":"score","scoreType":"public hardware-channel reference","higherIsBetter":true}]'::jsonb
    ),
    (
      5,
      'GPU',
      '5060[ -]?ti',
      NULL,
      'NVIDIA GeForce RTX 5060 Ti',
      '3DMark Steel Nomad DX12',
      'UL Solutions 3DMark hardware review',
      'https://benchmarks.ul.com/hardware/gpu/NVIDIA%2BGeForce%2BRTX%2B5060%2BTi/review',
      'Board partner RTX 5060 Ti cards inherit the public GPU-class benchmark. Exact factory OC, cooling, and power limit can differ.',
      '[{"benchmarkName":"3DMark Steel Nomad DX12 Graphics Score","score":3546,"unit":"score","scoreType":"public hardware-channel reference","higherIsBetter":true}]'::jsonb
    ),
    (
      6,
      'GPU',
      '5060',
      '5060[ -]?ti',
      'NVIDIA GeForce RTX 5060',
      '3DMark Steel Nomad DX12',
      'UL Solutions 3DMark hardware review',
      'https://benchmarks.ul.com/hardware/gpu/NVIDIA%2BGeForce%2BRTX%2B5060/review',
      'Board partner RTX 5060 cards inherit the public GPU-class benchmark. Exact factory OC, cooling, and power limit can differ.',
      '[{"benchmarkName":"3DMark Steel Nomad DX12 Graphics Score","score":3170,"unit":"score","scoreType":"public hardware-channel reference","higherIsBetter":true}]'::jsonb
    ),
    (
      10,
      'CPU',
      '9950x3d2',
      NULL,
      'AMD Ryzen 9 9950X3D2',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+9+9950X3D2&id=7115',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":72338,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4679,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      11,
      'CPU',
      '9950x3d',
      '9950x3d2',
      'AMD Ryzen 9 9950X3D',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+9+9950X3D&id=6549',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":70135,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4740,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      12,
      'CPU',
      '9950x',
      '9950x3d',
      'AMD Ryzen 9 9950X',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+9+9950X&id=6211',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":65753,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4729,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      13,
      'CPU',
      '9900x3d',
      NULL,
      'AMD Ryzen 9 9900X3D',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+9+9900X3D&id=6548',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":56164,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4639,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      14,
      'CPU',
      '9800x3d',
      NULL,
      'AMD Ryzen 7 9800X3D',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+7+9800X3D&id=6344',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":39956,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4424,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      15,
      'CPU',
      '9700x',
      NULL,
      'AMD Ryzen 7 9700X',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+7+9700X&id=6205',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":37019,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4647,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      16,
      'CPU',
      '9600x',
      NULL,
      'AMD Ryzen 5 9600X',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=AMD+Ryzen+5+9600X&id=6199',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":30091,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4571,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      17,
      'CPU',
      '285k',
      NULL,
      'Intel Core Ultra 9 285K',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=Intel+Core+Ultra+9+285K&id=6296',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":67258,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":5087,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      18,
      'CPU',
      '265k',
      NULL,
      'Intel Core Ultra 7 265K',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=Intel+Core+Ultra+7+265K&id=6326',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":58620,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4928,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    ),
    (
      19,
      'CPU',
      '245k',
      NULL,
      'Intel Core Ultra 5 245K',
      'PassMark PerformanceTest CPU',
      'PassMark CPU Benchmark',
      'https://www.cpubenchmark.net/cpu.php?cpu=Intel+Core+Ultra+5+245K&id=6324',
      'Retail package, tray, and bundle rows inherit the public CPU-model benchmark.',
      '[{"benchmarkName":"PassMark CPU Mark","score":43119,"unit":"score","scoreType":"public submitted average","higherIsBetter":true},{"benchmarkName":"PassMark Single Thread Rating","score":4718,"unit":"score","scoreType":"public submitted average","higherIsBetter":true}]'::jsonb
    )
  ) AS refs(
    priority,
    category,
    include_pattern,
    exclude_pattern,
    reference_model,
    benchmark_family,
    source_name,
    source_url,
    notes,
    raw_benchmarks
  )
), latest_normalized AS (
  SELECT DISTINCT ON (b.part_id)
         b.id,
         b.part_id
  FROM benchmark_summaries b
  WHERE b.deleted_at IS NULL
    AND b.benchmark_key LIKE 'normalized-fit-v1:%'
  ORDER BY b.part_id, b.created_at DESC, b.id DESC
), matched_refs AS (
  SELECT DISTINCT ON (p.id)
         p.id AS part_id,
         latest_normalized.id AS benchmark_id,
         raw_refs.*
  FROM parts p
  JOIN latest_normalized
    ON latest_normalized.part_id = p.id
  JOIN raw_refs
    ON raw_refs.category = p.category
   AND lower(p.name) ~ raw_refs.include_pattern
   AND (raw_refs.exclude_pattern IS NULL OR lower(p.name) !~ raw_refs.exclude_pattern)
  WHERE p.status = 'ACTIVE'
    AND p.deleted_at IS NULL
  ORDER BY p.id, raw_refs.priority
)
UPDATE benchmark_summaries b
SET metadata = coalesce(b.metadata, '{}'::jsonb)
    || jsonb_build_object(
         'metadataVersion', 2,
         'rawBenchmarkCoverage', 'PUBLIC_RAW_BENCHMARK_SEEDED',
         'rawBenchmarkReferenceModel', matched_refs.reference_model,
         'rawBenchmarkFamily', matched_refs.benchmark_family,
         'rawBenchmarkSourceName', matched_refs.source_name,
         'rawBenchmarkSourceUrl', matched_refs.source_url,
         'rawBenchmarkSourceCheckedAt', '2026-07-01',
         'rawBenchmarkNotes', matched_refs.notes,
         'rawBenchmarks', matched_refs.raw_benchmarks
       ),
    updated_at = now()
FROM matched_refs
WHERE b.id = matched_refs.benchmark_id;

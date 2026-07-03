-- RAG quality v2 seed: tighten the remaining benchmark failure cases.
-- Embeddings are intentionally refreshed by the admin backfill endpoint, not Flyway.

WITH seed_rag(public_id, source_id, chunk_text, summary, score, metadata) AS (
  VALUES
    (
      '00000000-0000-4000-8000-000000015101',
      'requirement-counterexample-premium-with-user-budget',
      'Counterexample for premium wording with a concrete budget: if the user says 최고사양, 끝판왕 느낌, 하이엔드 감성, 제일 좋은, 최상급 그래픽카드, or 최대한 좋은 while also giving a concrete budget such as 200만원, 200으로, 300만원 안에서, 400 안쪽, or 500 이하, keep budgetPolicy as USER_BUDGET. Interpret the request as best possible within that budget, not OPEN_BUDGET.',
      'Requirement parse counterexample: premium wording with a concrete budget must remain USER_BUDGET, including shorthand budgets such as 200으로 or 400 안쪽.',
      0.97500,
      '{"sourceType":"INTERNAL_RULE","purpose":"REQUIREMENT_PARSE","title":"Premium wording with explicit budget counterexample","relatedFields":["budget","budgetPolicy","performanceTier"],"metadataVersion":4}'
    ),
    (
      '00000000-0000-4000-8000-000000015102',
      'requirement-example-gaming-resolution-refresh',
      'Examples: 배그 QHD 144Hz, qhd 배그랑 개발 IDE 같이 쓸 조용한 PC, 로스트아크 4K, 고주사율 FPS, qhd 옵션 타협 없음, fhd 240hz are gaming and display-performance requirements. Extract usageTags GAMING and resolution when present. Preserve mixed usageTags when gaming is combined with development, creator, or low-noise wording.',
      'Requirement parse examples: Korean gaming, resolution, refresh-rate, mixed workload, and option-level wording.',
      0.95500,
      '{"sourceType":"BENCHMARK","purpose":"REQUIREMENT_PARSE","title":"Gaming resolution and refresh parse examples","relatedFields":["usageTags","resolution","questions","mustHave"],"metadataVersion":4}'
    ),
    (
      '00000000-0000-4000-8000-000000015301',
      'build-rule-qhd-price-combined-evidence',
      'When a build recommendation asks for QHD gaming and price evidence together, combine the QHD GPU-priority rule with saved parts.price and price_snapshots. Use stored current prices for the total, include QHD gaming evidence, and do not refresh external shopping APIs during the user request.',
      'Build recommendation rule: QHD gaming recommendations should combine GPU priority with saved price snapshot evidence.',
      0.92500,
      '{"sourceType":"INTERNAL_RULE","purpose":"BUILD_RECOMMEND","title":"QHD gaming and saved price combined rule","relatedCategories":["GPU","CPU","PSU","CASE"],"relatedFields":["parts.price","price_snapshots","resolution"],"metadataVersion":1}'
    )
)
INSERT INTO rag_evidence (
  public_id,
  agent_session_id,
  source_id,
  chunk_text,
  summary,
  score,
  metadata,
  created_at
)
SELECT
  public_id::uuid,
  NULL,
  source_id,
  chunk_text,
  summary,
  score,
  metadata::jsonb,
  now()
FROM seed_rag
ON CONFLICT (public_id) DO UPDATE SET
  agent_session_id = NULL,
  source_id = EXCLUDED.source_id,
  chunk_text = EXCLUDED.chunk_text,
  summary = EXCLUDED.summary,
  score = EXCLUDED.score,
  metadata = EXCLUDED.metadata,
  created_at = rag_evidence.created_at;

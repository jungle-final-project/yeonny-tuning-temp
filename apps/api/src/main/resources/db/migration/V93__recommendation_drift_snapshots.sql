-- M3 Drift·품질 모니터링(설계 docs/mlops-maturity-design.md §4).
-- 매일 1행: 카탈로그 피처 PSI·예측 drift PSI·운영 지표를 스냅샷으로 기록. snapshot_date UNIQUE로
-- 수동 재실행/재기동 시 ON CONFLICT DO UPDATE 멱등 기록.
CREATE TABLE recommendation_drift_snapshots (
  id BIGSERIAL PRIMARY KEY,
  snapshot_date DATE NOT NULL UNIQUE,
  metrics JSONB NOT NULL,
  alerts JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_recommendation_drift_snapshots_date
  ON recommendation_drift_snapshots(snapshot_date DESC);

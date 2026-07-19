-- 부하 실측(2026-07-19) 후속: 후보 전수 로드 경로(GET /api/parts 호환성 모드,
-- PartCompatibleCandidateService.activeCandidates)가 요청마다
--   WHERE category = ? AND status = 'ACTIVE' AND deleted_at IS NULL ORDER BY price, id
-- 를 돌린다. 기존 단일 컬럼 인덱스(V3)로는 정렬을 인덱스로 못 받아 요청마다 Sort가 붙었다.
-- ACTIVE·미삭제 행만 담는 partial 복합 인덱스로 필터+정렬을 한 번에 서빙한다.
CREATE INDEX IF NOT EXISTS ix_parts_active_category_price_id
    ON parts (category, price, id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

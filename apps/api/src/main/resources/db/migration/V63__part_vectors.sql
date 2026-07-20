/* 부품별 연계되는 속성(백터)를 저장하는 테이블 */
CREATE TABLE part_vectors (
    part_id BIGINT PRIMARY KEY REFERENCES parts(id),
    performance_score NUMERIC(5, 4) NOT NULL,
    value_score NUMERIC(5, 4) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
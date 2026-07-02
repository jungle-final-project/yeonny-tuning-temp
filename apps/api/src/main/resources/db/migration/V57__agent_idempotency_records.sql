CREATE TABLE IF NOT EXISTS agent_idempotency_records (
  id BIGSERIAL PRIMARY KEY,
  agent_device_id BIGINT NOT NULL REFERENCES agent_devices(id),
  idempotency_key VARCHAR(160) NOT NULL,
  request_method VARCHAR(10) NOT NULL,
  request_path VARCHAR(512) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_status INTEGER,
  response_content_type VARCHAR(120),
  response_body TEXT,
  status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_agent_idempotency_status
    CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  CONSTRAINT uq_agent_idempotency_scope
    UNIQUE (agent_device_id, request_method, request_path, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_agent_idempotency_expires_at
  ON agent_idempotency_records (expires_at);

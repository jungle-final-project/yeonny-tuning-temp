ALTER TABLE pc_agent_diagnosis_requests
  ADD COLUMN IF NOT EXISTS request_status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED',
  ADD COLUMN IF NOT EXISTS connection_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN IF NOT EXISTS accepted_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE pc_agent_diagnosis_requests
  DROP CONSTRAINT IF EXISTS chk_pc_agent_diagnosis_request_status;

ALTER TABLE pc_agent_diagnosis_requests
  ADD CONSTRAINT chk_pc_agent_diagnosis_request_status CHECK (
    request_status IN (
      'REQUESTED', 'ACCEPTED', 'DUPLICATE', 'EXPIRED', 'DEVICE_MISMATCH',
      'AUTH_FAILED', 'BUSY', 'REJECTED', 'DISPATCH_FAILED'
    )
  );

ALTER TABLE pc_agent_diagnosis_requests
  DROP CONSTRAINT IF EXISTS chk_pc_agent_diagnosis_connection_status;

ALTER TABLE pc_agent_diagnosis_requests
  ADD CONSTRAINT chk_pc_agent_diagnosis_connection_status CHECK (
    connection_status IN ('UNKNOWN', 'CONNECTED', 'DISCONNECTED')
  );

CREATE TABLE IF NOT EXISTS pc_agent_diagnosis_events (
  id BIGSERIAL PRIMARY KEY,
  diagnosis_id UUID NOT NULL REFERENCES pc_agent_diagnosis_requests(diagnosis_id) ON DELETE CASCADE,
  event_id VARCHAR(128) NOT NULL,
  task_id VARCHAR(128),
  event_type VARCHAR(80) NOT NULL,
  status VARCHAR(40) NOT NULL,
  progress_percent SMALLINT NOT NULL CHECK (progress_percent BETWEEN 0 AND 100),
  message TEXT,
  occurred_at TIMESTAMPTZ NOT NULL,
  raw_payload JSONB NOT NULL CHECK (jsonb_typeof(raw_payload) = 'object'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pc_agent_diagnosis_events_event_id UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS ix_pc_agent_diagnosis_events_history
  ON pc_agent_diagnosis_events(diagnosis_id, occurred_at, id);

CREATE TABLE IF NOT EXISTS pc_agent_diagnosis_results (
  id BIGSERIAL PRIMARY KEY,
  diagnosis_id UUID NOT NULL REFERENCES pc_agent_diagnosis_requests(diagnosis_id) ON DELETE CASCADE,
  result_id VARCHAR(128) NOT NULL,
  diagnosis_type VARCHAR(100),
  severity VARCHAR(30) NOT NULL,
  title TEXT NOT NULL,
  summary TEXT NOT NULL,
  resolution_type VARCHAR(50) NOT NULL,
  can_auto_recover BOOLEAN NOT NULL,
  evidence JSONB NOT NULL CHECK (jsonb_typeof(evidence) = 'array'),
  findings JSONB NOT NULL CHECK (jsonb_typeof(findings) = 'array'),
  actions JSONB NOT NULL CHECK (jsonb_typeof(actions) = 'array'),
  data_mode VARCHAR(10) NOT NULL CHECK (data_mode IN ('LIVE', 'DEMO')),
  scenario_id VARCHAR(100),
  raw_payload JSONB NOT NULL CHECK (jsonb_typeof(raw_payload) = 'object'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_pc_agent_diagnosis_results_diagnosis_id UNIQUE (diagnosis_id),
  CONSTRAINT uq_pc_agent_diagnosis_results_result_id UNIQUE (result_id),
  CONSTRAINT chk_pc_agent_diagnosis_results_demo_scenario CHECK (
    data_mode <> 'DEMO' OR scenario_id IS NOT NULL
  )
);

CREATE INDEX IF NOT EXISTS ix_pc_agent_diagnosis_results_created
  ON pc_agent_diagnosis_results(created_at DESC);

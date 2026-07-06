ALTER TABLE agent_log_uploads
  ADD COLUMN IF NOT EXISTS incident_window JSONB,
  ADD COLUMN IF NOT EXISTS range_started_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS range_ended_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_log_uploads_range_window
  ON agent_log_uploads(range_started_at, range_ended_at);

ALTER TABLE as_tickets
  ADD COLUMN IF NOT EXISTS ai_diagnosis_request JSONB,
  ADD COLUMN IF NOT EXISTS exception_approval_reason TEXT,
  ADD COLUMN IF NOT EXISTS exception_responsibility_scope TEXT,
  ADD COLUMN IF NOT EXISTS exception_user_message TEXT,
  ADD COLUMN IF NOT EXISTS exception_approved_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS exception_approved_by BIGINT REFERENCES users(id);

CREATE INDEX IF NOT EXISTS idx_as_tickets_exception_approved_by
  ON as_tickets(exception_approved_by);

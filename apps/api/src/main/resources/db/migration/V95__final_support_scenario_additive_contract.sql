ALTER TABLE agent_consents
  DROP CONSTRAINT IF EXISTS chk_agent_consents_type;

ALTER TABLE agent_consents
  ADD CONSTRAINT chk_agent_consents_type CHECK (
    consent_type IN (
      'LOCAL_COLLECTION',
      'SERVER_UPLOAD',
      'QUALITY_IMPROVEMENT',
      'REMOTE_CONNECTION',
      'REMOTE_FULL_CONTROL',
      'HIGH_RISK_REMOTE_ACTION'
    )
  );

ALTER TABLE agent_consents
  ADD COLUMN IF NOT EXISTS as_ticket_id BIGINT REFERENCES as_tickets(id),
  ADD COLUMN IF NOT EXISTS remote_session_id BIGINT REFERENCES remote_support_sessions(id),
  ADD COLUMN IF NOT EXISTS action_code VARCHAR(80),
  ADD COLUMN IF NOT EXISTS playbook_id VARCHAR(120),
  ADD COLUMN IF NOT EXISTS risk_notice_version VARCHAR(80);

CREATE INDEX IF NOT EXISTS idx_agent_consents_ticket_type_created_at
  ON agent_consents(as_ticket_id, consent_type, created_at);

CREATE INDEX IF NOT EXISTS idx_agent_consents_remote_session_type_created_at
  ON agent_consents(remote_session_id, consent_type, created_at);

ALTER TABLE remote_support_sessions
  ADD COLUMN IF NOT EXISTS requested_by_user_id BIGINT REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS request_reason TEXT,
  ADD COLUMN IF NOT EXISTS contact_phone_snapshot VARCHAR(50),
  ADD COLUMN IF NOT EXISTS user_requested_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS connection_consent_id BIGINT REFERENCES agent_consents(id),
  ADD COLUMN IF NOT EXISTS full_control_consent_id BIGINT REFERENCES agent_consents(id),
  ADD COLUMN IF NOT EXISTS high_risk_consent_id BIGINT REFERENCES agent_consents(id),
  ADD COLUMN IF NOT EXISTS action_code VARCHAR(80),
  ADD COLUMN IF NOT EXISTS playbook_id VARCHAR(120),
  ADD COLUMN IF NOT EXISTS risk_notice_version VARCHAR(80),
  ADD COLUMN IF NOT EXISTS full_control_allowed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS full_control_revoked_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS ended_reason TEXT,
  ADD COLUMN IF NOT EXISTS audit_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_remote_support_sessions_user_requested_by
  ON remote_support_sessions(requested_by_user_id, user_requested_at);

CREATE INDEX IF NOT EXISTS idx_remote_support_sessions_active_request
  ON remote_support_sessions(as_ticket_id, status);

ALTER TABLE as_tickets
  ADD COLUMN IF NOT EXISTS safety_advice_level VARCHAR(40),
  ADD COLUMN IF NOT EXISTS safety_notices JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS feedback_rating INTEGER,
  ADD COLUMN IF NOT EXISTS feedback_comment TEXT,
  ADD COLUMN IF NOT EXISTS feedback_created_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS diagnostic_accuracy VARCHAR(40);

ALTER TABLE as_tickets
  DROP CONSTRAINT IF EXISTS chk_as_tickets_feedback_rating;

ALTER TABLE as_tickets
  ADD CONSTRAINT chk_as_tickets_feedback_rating CHECK (
    feedback_rating IS NULL OR feedback_rating BETWEEN 1 AND 5
  );

ALTER TABLE as_tickets
  DROP CONSTRAINT IF EXISTS chk_as_tickets_diagnostic_accuracy;

ALTER TABLE as_tickets
  ADD CONSTRAINT chk_as_tickets_diagnostic_accuracy CHECK (
    diagnostic_accuracy IS NULL
    OR diagnostic_accuracy IN ('ACCURATE', 'PARTIAL', 'MISSED', 'UNKNOWN')
  );

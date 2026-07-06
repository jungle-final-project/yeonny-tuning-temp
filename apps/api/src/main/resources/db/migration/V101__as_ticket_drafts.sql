CREATE TABLE IF NOT EXISTS as_ticket_drafts (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES users(id),
    device_id BIGINT REFERENCES agent_devices(id),
    upload_job_id BIGINT REFERENCES agent_upload_jobs(id),
    log_upload_id BIGINT REFERENCES agent_log_uploads(id),
    idempotency_key VARCHAR(160),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(255) NOT NULL,
    detail_description TEXT NOT NULL,
    symptom_type VARCHAR(80) NOT NULL,
    symptom VARCHAR(2000) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    incident_window JSONB NOT NULL DEFAULT '{}'::jsonb,
    support_request_kind VARCHAR(40) NOT NULL DEFAULT 'DIAGNOSIS_ONLY',
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '1 day',
    submitted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_as_ticket_drafts_device_idempotency
    ON as_ticket_drafts(device_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_as_ticket_drafts_user_status
    ON as_ticket_drafts(user_id, status, created_at DESC);

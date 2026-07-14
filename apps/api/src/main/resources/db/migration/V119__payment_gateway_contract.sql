ALTER TABLE assembly_payments
    ADD COLUMN provider VARCHAR(24) NOT NULL DEFAULT 'LEGACY_VIRTUAL',
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'KRW',
    ADD COLUMN paid_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN verified_at TIMESTAMPTZ;

ALTER TABLE assembly_payments DROP CONSTRAINT IF EXISTS chk_assembly_payments_method;
ALTER TABLE assembly_payments DROP CONSTRAINT IF EXISTS assembly_payments_method_check;
ALTER TABLE assembly_payments
    ADD CONSTRAINT assembly_payments_method_check CHECK (method IN ('VIRTUAL', 'CARD', 'EASY_PAY')),
    ADD CONSTRAINT assembly_payments_provider_check CHECK (provider IN ('LEGACY_VIRTUAL', 'MOCK', 'PORTONE_V2')),
    ADD CONSTRAINT assembly_payments_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    ADD CONSTRAINT assembly_payments_paid_amount_check CHECK (paid_amount >= 0 AND paid_amount <= amount);

CREATE TABLE assembly_payment_attempts (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    assembly_payment_id BIGINT NOT NULL REFERENCES assembly_payments(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(120) NOT NULL,
    provider VARCHAR(24) NOT NULL,
    merchant_payment_id VARCHAR(120) NOT NULL UNIQUE,
    provider_transaction_id VARCHAR(160) UNIQUE,
    pg_transaction_id VARCHAR(160),
    pay_method VARCHAR(24) NOT NULL,
    easy_pay_provider VARCHAR(24),
    requested_amount BIGINT NOT NULL CHECK (requested_amount > 0),
    approved_amount BIGINT CHECK (approved_amount >= 0),
    currency VARCHAR(3) NOT NULL DEFAULT 'KRW' CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(24) NOT NULL,
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT assembly_payment_attempts_idempotency_unique UNIQUE (assembly_payment_id, idempotency_key),
    CONSTRAINT assembly_payment_attempts_provider_check CHECK (provider IN ('MOCK', 'PORTONE_V2')),
    CONSTRAINT assembly_payment_attempts_method_check CHECK (pay_method IN ('CARD', 'EASY_PAY')),
    CONSTRAINT assembly_payment_attempts_easy_pay_check CHECK (
        (pay_method = 'CARD' AND easy_pay_provider IS NULL)
        OR (pay_method = 'EASY_PAY' AND easy_pay_provider IN ('KAKAOPAY', 'TOSSPAY'))
    ),
    CONSTRAINT assembly_payment_attempts_status_check CHECK (
        status IN ('READY', 'PROCESSING', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED')
    )
);

CREATE UNIQUE INDEX ux_assembly_payment_attempts_succeeded
    ON assembly_payment_attempts (assembly_payment_id)
    WHERE status = 'SUCCEEDED';
CREATE INDEX idx_assembly_payment_attempts_latest
    ON assembly_payment_attempts (assembly_payment_id, created_at DESC, id DESC);
CREATE INDEX idx_assembly_payment_attempts_active
    ON assembly_payment_attempts (expires_at)
    WHERE status IN ('READY', 'PROCESSING', 'VERIFYING');

CREATE TABLE payment_webhook_events (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(24) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    merchant_payment_id VARCHAR(120),
    payload_hash VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    signature_verified BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    error_message VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_webhook_events_provider_check CHECK (provider IN ('MOCK', 'PORTONE_V2')),
    CONSTRAINT payment_webhook_events_status_check CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED', 'IGNORED')),
    CONSTRAINT payment_webhook_events_provider_event_unique UNIQUE (provider, event_id)
);

CREATE INDEX idx_payment_webhook_events_unprocessed
    ON payment_webhook_events (received_at, id)
    WHERE status IN ('RECEIVED', 'FAILED');

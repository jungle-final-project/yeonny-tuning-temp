ALTER TABLE assembly_payments DROP CONSTRAINT IF EXISTS assembly_payments_method_check;
ALTER TABLE assembly_payments DROP CONSTRAINT IF EXISTS assembly_payments_provider_check;
ALTER TABLE assembly_payments
    ADD CONSTRAINT assembly_payments_method_check CHECK (method IN ('VIRTUAL', 'CARD', 'EASY_PAY', 'POINT')),
    ADD CONSTRAINT assembly_payments_provider_check CHECK (provider IN ('LEGACY_VIRTUAL', 'MOCK', 'PORTONE_V2', 'BUILDGRAPH_POINT'));

ALTER TABLE assembly_payment_attempts DROP CONSTRAINT IF EXISTS assembly_payment_attempts_provider_check;
ALTER TABLE assembly_payment_attempts DROP CONSTRAINT IF EXISTS assembly_payment_attempts_method_check;
ALTER TABLE assembly_payment_attempts DROP CONSTRAINT IF EXISTS assembly_payment_attempts_easy_pay_check;
ALTER TABLE assembly_payment_attempts
    ADD CONSTRAINT assembly_payment_attempts_provider_check CHECK (provider IN ('MOCK', 'PORTONE_V2', 'BUILDGRAPH_POINT')),
    ADD CONSTRAINT assembly_payment_attempts_method_check CHECK (pay_method IN ('CARD', 'EASY_PAY', 'POINT')),
    ADD CONSTRAINT assembly_payment_attempts_easy_pay_check CHECK (
        (pay_method = 'CARD' AND easy_pay_provider IS NULL)
        OR (pay_method = 'EASY_PAY' AND easy_pay_provider IN ('KAKAOPAY', 'TOSSPAY'))
        OR (pay_method = 'POINT' AND easy_pay_provider IS NULL)
    );

CREATE TABLE user_point_accounts (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE point_transactions (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    point_account_id BIGINT NOT NULL REFERENCES user_point_accounts(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    assembly_payment_id BIGINT REFERENCES assembly_payments(id) ON DELETE RESTRICT,
    payment_attempt_id BIGINT REFERENCES assembly_payment_attempts(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(160) NOT NULL,
    transaction_type VARCHAR(16) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    balance_after BIGINT NOT NULL CHECK (balance_after >= 0),
    description VARCHAR(240) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT point_transactions_type_check CHECK (transaction_type IN ('GRANT', 'DEBIT', 'REFUND')),
    CONSTRAINT point_transactions_idempotency_unique UNIQUE (point_account_id, idempotency_key)
);

CREATE INDEX idx_point_transactions_user_created
    ON point_transactions (user_id, created_at DESC, id DESC);
CREATE INDEX idx_point_transactions_payment
    ON point_transactions (assembly_payment_id, created_at, id);

INSERT INTO user_point_accounts (user_id, balance)
SELECT id, 50000000
FROM users
WHERE email = 'user@example.com'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO point_transactions (
    point_account_id, user_id, idempotency_key, transaction_type, amount, balance_after, description
)
SELECT account.id, account.user_id, 'seed-demo-50000000', 'GRANT', 50000000, account.balance,
       '데모 포인트 50,000,000P 지급'
FROM user_point_accounts account
JOIN users user_account ON user_account.id = account.user_id
WHERE user_account.email = 'user@example.com'
ON CONFLICT (point_account_id, idempotency_key) DO NOTHING;

-- Keep the demo administrator funded without affecting other users.
INSERT INTO user_point_accounts (user_id, balance)
SELECT id, 0
FROM users
WHERE email = 'admin@example.com'
ON CONFLICT (user_id) DO NOTHING;

WITH target_account AS (
    SELECT account.id, account.user_id, account.balance
    FROM user_point_accounts account
    JOIN users user_account ON user_account.id = account.user_id
    WHERE user_account.email = 'admin@example.com'
      AND account.balance < 100000000
    FOR UPDATE
), credited_account AS (
    UPDATE user_point_accounts account
    SET balance = 100000000,
        updated_at = now()
    FROM target_account target
    WHERE account.id = target.id
    RETURNING account.id, account.user_id
)
INSERT INTO point_transactions (
    point_account_id,
    user_id,
    idempotency_key,
    transaction_type,
    amount,
    balance_after,
    description
)
SELECT credited.id,
       credited.user_id,
       'seed-admin-demo-100000000',
       'GRANT',
       100000000 - target.balance,
       100000000,
       'Demo administrator point balance grant'
FROM credited_account credited
JOIN target_account target ON target.id = credited.id
ON CONFLICT (point_account_id, idempotency_key) DO NOTHING;

package com.buildgraph.prototype.assembly;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BuildGraphPointService {
    private static final String POINT_NAME = "포인트";

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final TransactionTemplate transactionTemplate;

    public BuildGraphPointService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.transactionTemplate = transactionTemplate;
    }

    public Map<String, Object> wallet(String authorization) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        Map<String, Object> result = transactionTemplate.execute(status -> walletMap(requireAccountForUpdate(user.internalId())));
        if (result == null) {
            throw new IllegalStateException("포인트 지갑 조회 결과가 없습니다.");
        }
        return result;
    }

    public Map<String, Object> pay(
            String authorization,
            String requestPublicId,
            String idempotencyKey
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        String key = requiredText(idempotencyKey, "Idempotency-Key가 필요합니다.");
        if (key.length() > 120) {
            throw validation("Idempotency-Key는 120자 이하여야 합니다.");
        }
        Map<String, Object> result = transactionTemplate.execute(
                status -> payWithinTransaction(user.internalId(), requestPublicId, key)
        );
        if (result == null) {
            throw new IllegalStateException("포인트 결제 결과가 없습니다.");
        }
        return result;
    }

    @Transactional
    public boolean refundPaidPointPayment(Long requestId) {
        Map<String, Object> payment = jdbcTemplate.queryForList("""
                SELECT ap.id AS payment_id, ap.paid_amount, ap.provider, ap.status,
                       ar.user_id, account.id AS account_id, account.balance, account.updated_at
                FROM assembly_payments ap
                JOIN assembly_requests ar ON ar.id = ap.assembly_request_id
                JOIN user_point_accounts account ON account.user_id = ar.user_id
                WHERE ar.id = ?
                FOR UPDATE OF ap, account
                """, requestId).stream().findFirst().orElse(null);
        if (payment == null || !"PAID".equals(text(payment, "status"))) {
            return false;
        }
        if (!"BUILDGRAPH_POINT".equals(text(payment, "provider"))) {
            return false;
        }

        Long paymentId = longValue(payment, "payment_id");
        Long accountId = longValue(payment, "account_id");
        Map<String, Object> existing = jdbcTemplate.queryForList("""
                SELECT id FROM point_transactions
                WHERE point_account_id = ? AND idempotency_key = ?
                """, accountId, refundKey(paymentId)).stream().findFirst().orElse(null);
        if (existing != null) {
            return true;
        }

        long amount = longValue(payment, "paid_amount");
        long balanceAfter = jdbcTemplate.queryForObject("""
                UPDATE user_point_accounts
                SET balance = balance + ?, updated_at = now()
                WHERE id = ?
                RETURNING balance
                """, Long.class, amount, accountId);
        jdbcTemplate.update("""
                INSERT INTO point_transactions (
                    point_account_id, user_id, assembly_payment_id, idempotency_key,
                    transaction_type, amount, balance_after, description
                ) VALUES (?, ?, ?, ?, 'REFUND', ?, ?, '조립 요청 취소 포인트 복원')
                """, accountId, longValue(payment, "user_id"), paymentId, refundKey(paymentId), amount, balanceAfter);
        jdbcTemplate.update("""
                UPDATE assembly_payments
                SET status = 'REFUNDED', refunded_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PAID' AND provider = 'BUILDGRAPH_POINT'
                """, paymentId);
        return true;
    }

    private Map<String, Object> payWithinTransaction(Long userId, String requestPublicId, String idempotencyKey) {
        Map<String, Object> account = requireAccountForUpdate(userId);
        Map<String, Object> payment = jdbcTemplate.queryForList("""
                SELECT ar.id AS request_id, ar.request_no, ar.status AS request_status,
                       ap.id AS payment_id, ap.status AS payment_status, ap.amount, ap.currency
                FROM assembly_requests ar
                JOIN assembly_payments ap ON ap.assembly_request_id = ar.id
                WHERE ar.public_id = ?::uuid AND ar.user_id = ?
                FOR UPDATE OF ar, ap
                """, requestPublicId, userId).stream().findFirst().orElseThrow(this::notFound);

        Map<String, Object> existing = jdbcTemplate.queryForList("""
                SELECT assembly_payment_id, payment_attempt_id
                FROM point_transactions
                WHERE point_account_id = ? AND idempotency_key = ?
                """, longValue(account, "id"), idempotencyKey).stream().findFirst().orElse(null);
        if (existing != null) {
            if (!longValue(payment, "payment_id").equals(longValue(existing, "assembly_payment_id"))) {
                throw conflict("같은 Idempotency-Key를 다른 결제에 사용할 수 없습니다.");
            }
            return resultMap(reloadAttempt(longValue(existing, "payment_attempt_id")), account);
        }

        if (!"MATCHED".equals(text(payment, "request_status"))) {
            throw conflict("기사 매칭 후에만 포인트 결제를 시작할 수 있습니다.");
        }
        if ("PAID".equals(text(payment, "payment_status"))) {
            throw conflict("이미 결제가 완료되었습니다.");
        }
        if (!"PENDING".equals(text(payment, "payment_status"))) {
            throw conflict("포인트 결제를 시작할 수 없는 상태입니다.");
        }

        long amount = longValue(payment, "amount");
        long balance = longValue(account, "balance");
        if (balance < amount) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "INSUFFICIENT_POINTS",
                    "보유 포인트가 결제 금액보다 부족합니다.",
                    Map.of("balance", balance, "requiredPoints", amount)
            );
        }

        jdbcTemplate.update("""
                UPDATE assembly_payment_attempts
                SET status = 'CANCELLED', failure_code = 'PAYMENT_METHOD_CHANGED',
                    failure_message = '포인트 결제로 결제수단이 변경되었습니다.',
                    completed_at = now(), updated_at = now()
                WHERE assembly_payment_id = ? AND status IN ('READY', 'PROCESSING', 'VERIFYING')
                """, longValue(payment, "payment_id"));

        long balanceAfter = jdbcTemplate.queryForObject("""
                UPDATE user_point_accounts
                SET balance = balance - ?, updated_at = now()
                WHERE id = ? AND balance >= ?
                RETURNING balance
                """, Long.class, amount, longValue(account, "id"), amount);
        String merchantPaymentId = "BG-POINT-" + UUID.randomUUID().toString().replace("-", "");
        String providerTransactionId = "POINT-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> attempt = jdbcTemplate.queryForList("""
                INSERT INTO assembly_payment_attempts (
                    assembly_payment_id, idempotency_key, provider, merchant_payment_id,
                    provider_transaction_id, pay_method, requested_amount, approved_amount,
                    currency, status, expires_at, verified_at, completed_at
                ) VALUES (?, ?, 'BUILDGRAPH_POINT', ?, ?, 'POINT', ?, ?, ?, 'SUCCEEDED',
                          now() + interval '30 minutes', now(), now())
                RETURNING *
                """, longValue(payment, "payment_id"), idempotencyKey, merchantPaymentId,
                providerTransactionId, amount, amount, text(payment, "currency"))
                .stream().findFirst().orElseThrow();

        jdbcTemplate.update("""
                UPDATE assembly_payments
                SET provider = 'BUILDGRAPH_POINT', method = 'POINT', paid_amount = amount,
                    status = 'PAID', paid_at = now(), verified_at = now(), updated_at = now()
                WHERE id = ? AND status = 'PENDING'
                """, longValue(payment, "payment_id"));
        jdbcTemplate.update("""
                INSERT INTO point_transactions (
                    point_account_id, user_id, assembly_payment_id, payment_attempt_id,
                    idempotency_key, transaction_type, amount, balance_after, description
                ) VALUES (?, ?, ?, ?, ?, 'DEBIT', ?, ?, ?)
                """, longValue(account, "id"), userId, longValue(payment, "payment_id"),
                longValue(attempt, "id"), idempotencyKey, amount, balanceAfter,
                "조립 " + text(payment, "request_no") + " 포인트 결제");

        account.put("balance", balanceAfter);
        account.put("updated_at", attempt.get("completed_at"));
        return resultMap(attempt, account);
    }

    private Map<String, Object> requireAccountForUpdate(Long userId) {
        jdbcTemplate.update("""
                INSERT INTO user_point_accounts (user_id, balance)
                VALUES (?, 0)
                ON CONFLICT (user_id) DO NOTHING
                """, userId);
        return jdbcTemplate.queryForList("""
                SELECT id, public_id::text AS public_id, balance, updated_at
                FROM user_point_accounts
                WHERE user_id = ?
                FOR UPDATE
                """, userId).stream().findFirst().orElseThrow();
    }

    private Map<String, Object> reloadAttempt(Long attemptId) {
        return jdbcTemplate.queryForList("SELECT * FROM assembly_payment_attempts WHERE id = ?", attemptId)
                .stream().findFirst().orElseThrow(this::notFound);
    }

    private Map<String, Object> resultMap(Map<String, Object> attempt, Map<String, Object> account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attempt", attemptMap(attempt));
        result.put("wallet", walletMap(account));
        return result;
    }

    private Map<String, Object> walletMap(Map<String, Object> account) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", text(account, "public_id"));
        result.put("name", POINT_NAME);
        result.put("balance", longValue(account, "balance"));
        result.put("pointValueWon", 1);
        result.put("currency", "KRW");
        result.put("updatedAt", account.get("updated_at"));
        return result;
    }

    private Map<String, Object> attemptMap(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", text(row, "public_id"));
        result.put("provider", text(row, "provider"));
        result.put("merchantPaymentId", text(row, "merchant_payment_id"));
        result.put("providerTransactionId", text(row, "provider_transaction_id"));
        result.put("pgTransactionId", text(row, "pg_transaction_id"));
        result.put("payMethod", text(row, "pay_method"));
        result.put("easyPayProvider", text(row, "easy_pay_provider"));
        result.put("requestedAmount", longValue(row, "requested_amount"));
        result.put("approvedAmount", longValue(row, "approved_amount"));
        result.put("currency", text(row, "currency"));
        result.put("status", text(row, "status"));
        result.put("failureCode", text(row, "failure_code"));
        result.put("failureMessage", text(row, "failure_message"));
        result.put("expiresAt", row.get("expires_at"));
        result.put("verifiedAt", row.get("verified_at"));
        result.put("completedAt", row.get("completed_at"));
        result.put("createdAt", row.get("created_at"));
        result.put("updatedAt", row.get("updated_at"));
        return result;
    }

    private static String refundKey(Long paymentId) {
        return "refund-assembly-payment-" + paymentId;
    }

    private static String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.trim();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "포인트 결제 정보를 찾을 수 없습니다.");
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT_STATE", message);
    }
}

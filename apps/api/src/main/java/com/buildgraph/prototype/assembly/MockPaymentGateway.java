package com.buildgraph.prototype.assembly;

import com.buildgraph.prototype.common.ApiException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {
    private final boolean enabled;
    private final Map<String, MockTransaction> transactions = new ConcurrentHashMap<>();

    public MockPaymentGateway(@Value("${buildgraph.payment.mock.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public CheckoutSession createCheckout(CheckoutRequest request) {
        requireEnabled();
        MockTransaction transaction = transactions.computeIfAbsent(request.merchantPaymentId(), ignored ->
                new MockTransaction(
                        "mock_" + UUID.randomUUID().toString().replace("-", ""),
                        request,
                        MockOutcome.PENDING
                ));
        CheckoutRequest stored = transaction.request();
        return new CheckoutSession(
                provider(), transaction.providerTransactionId(), stored.merchantPaymentId(), stored.orderName(),
                stored.amount(), stored.currency(), stored.payMethod(), stored.easyPayProvider(), stored.expiresAt()
        );
    }

    @Override
    public VerificationResult verify(String merchantPaymentId) {
        requireEnabled();
        MockTransaction transaction = transactions.get(merchantPaymentId);
        if (transaction == null) {
            return new VerificationResult(
                    VerificationStatus.FAILED, null, null, merchantPaymentId, null, null, null, null,
                    "PAYMENT_NOT_FOUND", "Mock PG에서 결제 건을 찾을 수 없습니다."
            );
        }
        CheckoutRequest request = transaction.request();
        return switch (transaction.outcome()) {
            case PENDING -> result(transaction, VerificationStatus.PENDING, null, null);
            case SUCCESS -> result(transaction, VerificationStatus.PAID, null, null);
            case FAILURE -> result(transaction, VerificationStatus.FAILED, "MOCK_PAYMENT_FAILED", "테스트 결제가 실패했습니다.");
            case CANCEL -> result(transaction, VerificationStatus.CANCELLED, "MOCK_PAYMENT_CANCELLED", "사용자가 테스트 결제를 취소했습니다.");
        };
    }

    public void setOutcome(String merchantPaymentId, String result) {
        requireEnabled();
        MockOutcome outcome;
        try {
            outcome = MockOutcome.valueOf(result == null ? "" : result.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "result는 SUCCESS, FAILURE, CANCEL 중 하나여야 합니다.");
        }
        if (outcome == MockOutcome.PENDING) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "PENDING 결과는 직접 지정할 수 없습니다.");
        }
        transactions.compute(merchantPaymentId, (ignored, transaction) -> {
            if (transaction == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Mock PG 결제 건을 찾을 수 없습니다.");
            }
            return new MockTransaction(transaction.providerTransactionId(), transaction.request(), outcome);
        });
    }

    private VerificationResult result(
            MockTransaction transaction,
            VerificationStatus status,
            String failureCode,
            String failureMessage
    ) {
        CheckoutRequest request = transaction.request();
        return new VerificationResult(
                status,
                transaction.providerTransactionId(),
                status == VerificationStatus.PAID ? "mock_pg_" + transaction.providerTransactionId() : null,
                request.merchantPaymentId(),
                request.amount(),
                request.currency(),
                request.payMethod(),
                request.easyPayProvider(),
                failureCode,
                failureMessage
        );
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_DISABLED", "Mock 결제 기능이 비활성화되어 있습니다.");
        }
    }

    private enum MockOutcome {
        PENDING,
        SUCCESS,
        FAILURE,
        CANCEL
    }

    private record MockTransaction(
            String providerTransactionId,
            CheckoutRequest request,
            MockOutcome outcome
    ) {
    }
}

package com.buildgraph.prototype.assembly;

import java.time.OffsetDateTime;

public interface PaymentGateway {
    String provider();

    CheckoutSession createCheckout(CheckoutRequest request);

    VerificationResult verify(String merchantPaymentId);

    record CheckoutRequest(
            String merchantPaymentId,
            String orderName,
            long amount,
            String currency,
            String payMethod,
            String easyPayProvider,
            OffsetDateTime expiresAt
    ) {
    }

    record CheckoutSession(
            String provider,
            String providerTransactionId,
            String merchantPaymentId,
            String orderName,
            long amount,
            String currency,
            String payMethod,
            String easyPayProvider,
            OffsetDateTime expiresAt
    ) {
    }

    record VerificationResult(
            VerificationStatus status,
            String providerTransactionId,
            String pgTransactionId,
            String merchantPaymentId,
            Long amount,
            String currency,
            String payMethod,
            String easyPayProvider,
            String failureCode,
            String failureMessage
    ) {
    }

    enum VerificationStatus {
        PENDING,
        PAID,
        FAILED,
        CANCELLED
    }
}

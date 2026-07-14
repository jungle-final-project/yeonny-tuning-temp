package com.buildgraph.prototype.assembly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MockPaymentGatewayTest {
    private final MockPaymentGateway gateway = new MockPaymentGateway(true);

    @Test
    void checkoutIsIdempotentAndPaymentMustBeVerifiedAfterProviderResult() {
        PaymentGateway.CheckoutRequest request = new PaymentGateway.CheckoutRequest(
                "BG-test-payment", "BuildGraph 조립 ASM-TEST", 125_000L, "KRW", "EASY_PAY", "KAKAOPAY",
                OffsetDateTime.now().plusMinutes(30)
        );

        PaymentGateway.CheckoutSession first = gateway.createCheckout(request);
        PaymentGateway.CheckoutSession duplicate = gateway.createCheckout(request);
        assertThat(duplicate.providerTransactionId()).isEqualTo(first.providerTransactionId());
        assertThat(gateway.verify(request.merchantPaymentId()).status()).isEqualTo(PaymentGateway.VerificationStatus.PENDING);

        gateway.setOutcome(request.merchantPaymentId(), "SUCCESS");
        PaymentGateway.VerificationResult verified = gateway.verify(request.merchantPaymentId());

        assertThat(verified.status()).isEqualTo(PaymentGateway.VerificationStatus.PAID);
        assertThat(verified.amount()).isEqualTo(125_000L);
        assertThat(verified.currency()).isEqualTo("KRW");
        assertThat(verified.easyPayProvider()).isEqualTo("KAKAOPAY");
    }
}

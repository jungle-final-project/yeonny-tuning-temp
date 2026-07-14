package com.buildgraph.prototype.assembly;

import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AssemblyPaymentController {
    private final AssemblyPaymentService service;

    public AssemblyPaymentController(AssemblyPaymentService service) {
        this.service = service;
    }

    @PostMapping("/assembly-requests/{id}/payments/attempts")
    Map<String, Object> createAttempt(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.createAttempt(authorization, id, idempotencyKey, request);
    }

    @PostMapping("/payments/attempts/{id}/mock-result")
    Map<String, Object> mockResult(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.setMockResult(authorization, id, request);
    }

    @PostMapping("/payments/attempts/{id}/complete")
    Map<String, Object> complete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.completeAttempt(authorization, id);
    }

    @PostMapping("/payments/webhooks/mock")
    Map<String, Object> mockWebhook(
            @RequestHeader(value = "X-Mock-Webhook-Secret", required = false) String signature,
            @RequestBody String rawBody
    ) {
        return service.receiveMockWebhook(signature, rawBody);
    }
}

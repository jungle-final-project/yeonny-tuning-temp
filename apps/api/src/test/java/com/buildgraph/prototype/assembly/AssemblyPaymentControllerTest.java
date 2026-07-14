package com.buildgraph.prototype.assembly;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AssemblyPaymentController.class)
class AssemblyPaymentControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssemblyPaymentService service;

    @Test
    void createAttemptForwardsServerContract() throws Exception {
        when(service.createAttempt(eq(USER_TOKEN), eq("assembly-1"), eq("payment-key"), anyMap()))
                .thenReturn(Map.of("id", "attempt-1", "status", "PROCESSING", "requestedAmount", 125000));

        mockMvc.perform(post("/api/assembly-requests/assembly-1/payments/attempts")
                        .header("Authorization", USER_TOKEN)
                        .header("Idempotency-Key", "payment-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"KAKAOPAY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("attempt-1"))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(service).createAttempt(eq(USER_TOKEN), eq("assembly-1"), eq("payment-key"), anyMap());
    }

    @Test
    void browserCompletionAndWebhookHaveSeparateEntryPoints() throws Exception {
        when(service.completeAttempt(USER_TOKEN, "attempt-1"))
                .thenReturn(Map.of("id", "attempt-1", "status", "SUCCEEDED"));
        when(service.receiveMockWebhook(eq("test-secret"), eq("{\"eventId\":\"evt-1\"}")))
                .thenReturn(Map.of("eventId", "evt-1", "status", "PROCESSED"));

        mockMvc.perform(post("/api/payments/attempts/attempt-1/complete").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mockMvc.perform(post("/api/payments/webhooks/mock")
                        .header("X-Mock-Webhook-Secret", "test-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"evt-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }
}

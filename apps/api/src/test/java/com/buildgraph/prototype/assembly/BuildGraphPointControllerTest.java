package com.buildgraph.prototype.assembly;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BuildGraphPointController.class)
class BuildGraphPointControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BuildGraphPointService service;

    @Test
    void walletReturnsSeededPointBalance() throws Exception {
        when(service.wallet(USER_TOKEN)).thenReturn(Map.of(
                "id", "point-wallet-1",
                "name", "BuildGraph 포인트",
                "balance", 50_000_000,
                "pointValueWon", 1,
                "currency", "KRW"
        ));

        mockMvc.perform(get("/api/users/me/points").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BuildGraph 포인트"))
                .andExpect(jsonPath("$.balance").value(50_000_000));

        verify(service).wallet(USER_TOKEN);
    }

    @Test
    void payForwardsIdempotentServerContract() throws Exception {
        when(service.pay(USER_TOKEN, "assembly-1", "point-key")).thenReturn(Map.of(
                "attempt", Map.of("id", "attempt-1", "status", "SUCCEEDED"),
                "wallet", Map.of("id", "point-wallet-1", "balance", 49_875_000)
        ));

        mockMvc.perform(post("/api/assembly-requests/assembly-1/payments/points/confirm")
                        .header("Authorization", USER_TOKEN)
                        .header("Idempotency-Key", "point-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempt.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.wallet.balance").value(49_875_000));

        verify(service).pay(USER_TOKEN, "assembly-1", "point-key");
    }
}

package com.buildgraph.prototype.recommendation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PublicHomeController.class)
class PublicHomeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicHomeService publicHomeService;

    @Test
    void publicHomeIsAvailableWithoutAuthorization() throws Exception {
        when(publicHomeService.home()).thenReturn(Map.of(
                "categoryParts", Map.of("CPU", List.of(Map.of(
                        "id", "cpu-public-id",
                        "category", "CPU",
                        "name", "Public CPU",
                        "price", 100000
                ))),
                "recommendedParts", Map.of(
                        "items", List.of(),
                        "generatedAt", "2026-07-14T00:00:00Z",
                        "fallbackUsed", true
                )
        ));

        mockMvc.perform(get("/api/public/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryParts.CPU[0].name").value("Public CPU"))
                .andExpect(jsonPath("$.recommendedParts.fallbackUsed").value(true));

        verify(publicHomeService).home();
    }
}
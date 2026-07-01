package com.buildgraph.prototype.part;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(PartController.class)
class PartControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final String ADMIN_TOKEN = "Bearer jwt-admin-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartQueryService partQueryService;

    @MockitoBean
    private ToolCheckService toolCheckService;

    @MockitoBean
    private NaverShoppingOfferService naverShoppingOfferService;

    @MockitoBean
    private DanawaPriceSnapshotService danawaPriceSnapshotService;

    @MockitoBean
    private DanawaPriceTrendService danawaPriceTrendService;

    @MockitoBean
    private ManufacturerReleaseIntakeService manufacturerReleaseIntakeService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireAdmin(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    @Test
    void toolCheckReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(post("/api/tools/power/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(toolCheckService);
    }

    @Test
    void toolCheckRunsForAuthenticatedUserToken() throws Exception {
        when(toolCheckService.checkTool(eq("power"), anyMap())).thenReturn(Map.of(
                "tool", "power",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "전력 검증 통과",
                "details", Map.of("ratedHeadroomW", 180)
        ));

        mockMvc.perform(post("/api/tools/power/check")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tool").value("power"))
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.confidence").value("HIGH"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(toolCheckService).checkTool(eq("power"), anyMap());
    }

    @Test
    void manufacturerSourcesRequireAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/manufacturer-sources"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(manufacturerReleaseIntakeService);
    }

    @Test
    void createManufacturerSourceDelegatesToServiceForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.createSource(anyMap())).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000009001",
                "manufacturer", "ASUS",
                "categoryScope", "GPU",
                "sourceType", "NEWS",
                "sourceUrl", "https://www.asus.com/news/",
                "enabled", true,
                "status", "ACTIVE"
        ));

        mockMvc.perform(post("/api/admin/manufacturer-sources")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "manufacturer": "ASUS",
                                  "categoryScope": "GPU",
                                  "sourceType": "NEWS",
                                  "sourceUrl": "https://www.asus.com/news/",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manufacturer").value("ASUS"))
                .andExpect(jsonPath("$.categoryScope").value("GPU"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(manufacturerReleaseIntakeService).createSource(anyMap());
    }

    @Test
    void updateManufacturerSourceDelegatesToServiceForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.updateSource(eq("00000000-0000-4000-8000-000000009001"), anyMap()))
                .thenReturn(Map.of(
                        "id", "00000000-0000-4000-8000-000000009001",
                        "enabled", false,
                        "status", "PAUSED"
                ));

        mockMvc.perform(patch("/api/admin/manufacturer-sources/00000000-0000-4000-8000-000000009001")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"status\": \"PAUSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(manufacturerReleaseIntakeService).updateSource(eq("00000000-0000-4000-8000-000000009001"), anyMap());
    }
}

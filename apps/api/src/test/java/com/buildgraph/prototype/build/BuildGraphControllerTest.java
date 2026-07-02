package com.buildgraph.prototype.build;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
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

@WebMvcTest(BuildGraphController.class)
class BuildGraphControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BuildGraphService buildGraphService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    @Test
    void resolveReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(post("/api/build-graphs/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "AI_BUILD",
                                  "items": []
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(buildGraphService);
    }

    @Test
    void resolveReturnsDependencyGraphForAiBuildItems() throws Exception {
        when(buildGraphService.resolve(eq(USER_TOKEN), anyMap())).thenReturn(MockData.map(
                "mode", "BUILD_OVERVIEW",
                "summary", "GPU 선택으로 PSU와 케이스 조건을 확인해야 합니다.",
                "nodes", List.of(
                        node("part-gpu", "PART", "GPU", "RTX 5070", "PASS"),
                        node("constraint-power", "CONSTRAINT", "전력", "750W 이상 권장", "WARN")
                ),
                "edges", List.of(Map.of(
                        "id", "edge-gpu-psu-power",
                        "source", "part-gpu",
                        "target", "part-psu",
                        "type", "AFFECTS",
                        "status", "WARN",
                        "label", "전력 여유",
                        "summary", "GPU 권장 정격 파워를 기준으로 PSU 여유를 확인합니다."
                )),
                "focusNodeIds", List.of("part-gpu", "part-psu"),
                "insights", List.of(Map.of(
                        "id", "insight-power",
                        "status", "WARN",
                        "title", "파워 여유 확인",
                        "description", "750W 이상 파워를 권장합니다.",
                        "relatedNodeIds", List.of("part-gpu", "part-psu")
                )),
                "toolResults", List.of(Map.of(
                        "tool", "power",
                        "status", "WARN",
                        "confidence", "MEDIUM",
                        "summary", "PSU 정격 출력 여유가 낮습니다."
                ))
        ));

        mockMvc.perform(post("/api/build-graphs/resolve")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "AI_BUILD",
                                  "view": "FOCUSED",
                                  "budgetWon": 2000000,
                                  "items": [
                                    {
                                      "partId": "00000000-0000-4000-8000-000000000201",
                                      "category": "GPU",
                                      "quantity": 1
                                    }
                                  ],
                                  "focus": {
                                    "mode": "PART_IMPACT",
                                    "category": "GPU",
                                    "tool": "power"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("BUILD_OVERVIEW"))
                .andExpect(jsonPath("$.nodes[0].id").value("part-gpu"))
                .andExpect(jsonPath("$.edges[0].id").value("edge-gpu-psu-power"))
                .andExpect(jsonPath("$.insights[0].status").value("WARN"))
                .andExpect(jsonPath("$.toolResults[0].tool").value("power"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(buildGraphService).resolve(eq(USER_TOKEN), anyMap());
    }

    private static Map<String, Object> node(String id, String type, String category, String label, String status) {
        return Map.of(
                "id", id,
                "type", type,
                "category", category,
                "label", label,
                "status", status
        );
    }
}

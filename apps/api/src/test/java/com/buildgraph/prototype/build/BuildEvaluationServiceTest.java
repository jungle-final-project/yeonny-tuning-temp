package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolCheckService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BuildEvaluationServiceTest {
    @Test
    void currentDraftParsesPostgresJsonAttributesBeforeRunningTools() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), eq(42L)))
                .thenReturn(List.of(Map.of("internal_id", 7L)));
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString(), eq(7L)))
                .thenReturn(List.of(Map.of(
                        "internal_id", 31L,
                        "part_id", "psu-1500",
                        "category", "PSU",
                        "name", "Corsair HX1500i",
                        "manufacturer", "Corsair",
                        "current_price", 700_000,
                        "quantity", 1,
                        "attributes", "{\"capacityW\":1500,\"wattage\":1500,\"toolReady\":true}"
                )));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<?> parts = invocation.getArgument(0);
            com.buildgraph.prototype.part.ToolBuildPart psu =
                    (com.buildgraph.prototype.part.ToolBuildPart) parts.get(0);
            assertThat(psu.attributes())
                    .containsEntry("capacityW", 1500)
                    .containsEntry("wattage", 1500);
            return List.of(MockData.map(
                    "tool", "power",
                    "status", "PASS",
                    "confidence", "HIGH",
                    "summary", "파워 용량이 충분합니다.",
                    "details", Map.of("psuRatedCapacityW", 1500, "ratedHeadroomW", 900)
            ));
        });
        BuildEvaluationService service = new BuildEvaluationService(
                jdbcTemplate,
                toolCheckService,
                new BuildCompositeScoreService(),
                new BuildScoreAdviceService()
        );

        BuildEvaluationService.BuildEvaluation evaluation = service.evaluateCurrentDraft(42L, null, null, null);

        assertThat(evaluation.parts()).singleElement().satisfies(part ->
                assertThat(part.attributes()).containsEntry("capacityW", 1500));
        assertThat(evaluation.toolResults()).singleElement().satisfies(result ->
                assertThat(result).containsEntry("status", "PASS"));
    }
}

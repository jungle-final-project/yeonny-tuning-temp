package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildEvaluationServiceTest {
    @Test
    void evaluatesRecommendationSnapshotWithoutRepeatingToolCheck() {
        PartQuery partQuery = mock(PartQuery.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        BuildEvaluationService service = new BuildEvaluationService(
                partQuery,
                toolCheckService,
                new BuildCompositeScoreService(),
                new BuildScoreAdviceService(),
                null
        );
        List<com.buildgraph.prototype.part.tool.ToolBuildPart> parts = List.of(
                new com.buildgraph.prototype.part.tool.ToolBuildPart(
                        1L, "cpu-1", "CPU", "AMD Ryzen 7 9700X", "AMD", 500_000,
                        Map.of("cores", 8, "threads", 16), 1),
                new com.buildgraph.prototype.part.tool.ToolBuildPart(
                        2L, "gpu-1", "GPU", "RTX 5060", "NVIDIA", 500_000,
                        Map.of("gpuClass", "RTX_5060", "vramGb", 8), 1)
        );
        List<Map<String, Object>> toolResults = List.of(MockData.map(
                "tool", "compatibility",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "호환성 통과",
                "details", Map.of()
        ));

        BuildEvaluationService.BuildEvaluation evaluation = service.evaluateSnapshot(
                parts, toolResults, 2_000_000, null, null);

        assertThat(evaluation.compositeScore()).containsKeys("score", "grade", "label");
        assertThat(evaluation.toolResults()).hasSize(1);
        verifyNoInteractions(toolCheckService);
    }

    @Test
    void currentDraftUsesPartQueryBeforeRunningTools() {
        PartQuery partQuery = mock(PartQuery.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        when(partQuery.partsByActiveDraftUserId(42L)).thenReturn(List.of(new ToolBuildPart(
                31L,
                "psu-1500",
                "PSU",
                "Corsair HX1500i",
                "Corsair",
                700_000,
                Map.of("capacityW", 1500, "wattage", 1500, "toolReady", true),
                1
        )));
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<?> parts = invocation.getArgument(0);
            com.buildgraph.prototype.part.tool.ToolBuildPart psu =
                    (com.buildgraph.prototype.part.tool.ToolBuildPart) parts.get(0);
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
                partQuery,
                toolCheckService,
                new BuildCompositeScoreService(),
                new BuildScoreAdviceService(),
                null
        );

        BuildEvaluationService.BuildEvaluation evaluation = service.evaluateCurrentDraft(42L, null, null, null);

        assertThat(evaluation.parts()).singleElement().satisfies(part ->
                assertThat(part.attributes()).containsEntry("capacityW", 1500));
        assertThat(evaluation.toolResults()).singleElement().satisfies(result ->
                assertThat(result).containsEntry("status", "PASS"));
    }
}

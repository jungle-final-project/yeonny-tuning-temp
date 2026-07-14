package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PartQueryServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
    private final PartQueryService service = new PartQueryService(jdbcTemplate, compatibilityService);

    @Test
    void partsWithCompatibilitySortEvaluateAllRowsThenPaginateByStatusPriority() {
        List<Map<String, Object>> rawRows = List.of(
                part("gpu-warn", 101L, "GPU", "RTX 5080 Compact", 1_490_000),
                part("gpu-fail", 102L, "GPU", "RTX 5090 Long", 2_900_000),
                part("gpu-pass-cheap", 103L, "GPU", "RTX 5060 Unbalanced", 500_000),
                part("gpu-pass-balanced", 104L, "GPU", "RTX 5070 Ti Balanced", 990_000)
        );
        List<Map<String, Object>> evaluatedRows = List.of(
                withCompatibility(rawRows.get(0), "WARN", "간섭 주의"),
                withCompatibility(rawRows.get(1), "FAIL", "장착 불가"),
                withCompatibility(rawRows.get(2), "PASS", "호환 가능"),
                withCompatibility(rawRows.get(3), "PASS", "호환 가능")
        );
        CurrentUserService.CurrentUser user = user();
        when(jdbcTemplate.queryForList(anyString(), eq("GPU"))).thenReturn(rawRows);
        when(compatibilityService.partRowsWithCompatibility(eq(user), eq("QUOTE_DRAFT_CURRENT"), eq("GPU"), eq(null), eq(null), anyList()))
                .thenReturn(evaluatedRows);

        Map<String, Object> response = service.parts(
                user,
                "GPU",
                null,
                null,
                null,
                null,
                null,
                0,
                20,
                "compatibility",
                "QUOTE_DRAFT_CURRENT",
                null,
                null
        );

        List<Map<String, Object>> items = castList(response.get("items"));
        assertThat(items).extracting(item -> item.get("name"))
                .containsExactly("RTX 5070 Ti Balanced", "RTX 5060 Unbalanced", "RTX 5080 Compact", "RTX 5090 Long");
        assertThat(compatibility(items.get(0)).get("status")).isEqualTo("PASS");
        assertThat(compatibility(items.get(1)).get("status")).isEqualTo("PASS");
        assertThat(compatibility(items.get(2)).get("status")).isEqualTo("WARN");
        assertThat(compatibility(items.get(3)).get("status")).isEqualTo("FAIL");
        assertThat(recommendation(items.get(0)).get("rank")).isEqualTo(1);
        assertThat(recommendation(items.get(1)).get("rank")).isEqualTo(2);
        assertThat(items.get(0)).doesNotContainKeys("_candidateToolResults", "_recommendationContext", "internal_id");
        assertThat(response.get("total")).isEqualTo(4);
    }

    private static Map<String, Object> part(String publicId, long internalId, String category, String name, int price) {
        return MockData.map(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "status", "ACTIVE",
                "attributes", Map.of()
        );
    }

    private static Map<String, Object> withCompatibility(Map<String, Object> row, String status, String statusLabel) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
        copy.put("compatibility", MockData.map(
                "status", status,
                "statusLabel", statusLabel,
                "summary", "현재 조합 기준 평가 결과입니다.",
                "checkedTools", List.of("power", "size", "performance")
        ));
        copy.put("_recommendationContext", Map.of("selectedCategories", List.of("CPU")));
        copy.put("_candidateToolResults", List.of(MockData.map(
                "tool", "performance",
                "status", status,
                "details", MockData.map("cpuBenchmarkScore", 80, "gpuBenchmarkScore", String.valueOf(row.get("name")).contains("Unbalanced") ? 40 : 80)
        )));
        return copy;
    }

    private static CurrentUserService.CurrentUser user() {
        return new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                "2026-06-30T00:00:00Z"
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compatibility(Map<String, Object> part) {
        return (Map<String, Object>) part.get("compatibility");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> recommendation(Map<String, Object> part) {
        return (Map<String, Object>) part.get("recommendation");
    }
}

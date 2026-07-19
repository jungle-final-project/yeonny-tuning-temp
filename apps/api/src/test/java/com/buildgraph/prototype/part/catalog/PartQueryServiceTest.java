package com.buildgraph.prototype.part.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.query.PartDetailCachedLoader;
import com.buildgraph.prototype.part.query.PartDetailDto;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.recommendation.PartContextRecommendationService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PartQueryServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PartCompatibleCandidateService compatibilityService = mock(PartCompatibleCandidateService.class);
    private final PartDetailCachedLoader partDetailCachedLoader = mock(PartDetailCachedLoader.class);
    private final PartQueryService service = new PartQueryService(
            jdbcTemplate, compatibilityService, new PartContextRecommendationService(), partDetailCachedLoader);

    @Test
    void topActivePartsByCategoryPriceDescGroupsRowsByCategoryFromSingleQuery() {
        List<Map<String, Object>> rawRows = List.of(
                part("cpu-high", 201L, "CPU", "Ryzen 9", 720_000),
                part("gpu-high", 301L, "GPU", "RTX 5080", 1_490_000),
                part("gpu-mid", 302L, "GPU", "RTX 5070", 850_000)
        );
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rawRows);

        Map<String, Object> response = service.topActivePartsByCategoryPriceDesc(List.of("CPU", "GPU"), 4);

        assertThat(response.keySet()).containsExactly("CPU", "GPU");
        List<Map<String, Object>> cpus = castList(response.get("CPU"));
        List<Map<String, Object>> gpus = castList(response.get("GPU"));
        assertThat(cpus).extracting(item -> item.get("name")).containsExactly("Ryzen 9");
        assertThat(gpus).extracting(item -> item.get("name")).containsExactly("RTX 5080", "RTX 5070");
        assertThat(cpus.get(0)).doesNotContainKey("internal_id");
        assertThat(gpus.get(0)).doesNotContainKey("internal_id");
    }

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
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(rawRows.stream().map(row -> String.valueOf(row.get("id"))).toList());
        when(partDetailCachedLoader.detailsByPublicIds(anyList())).thenReturn(rawRows.stream()
                .map(row -> new PartDetailDto(
                        new ToolBuildPart(
                                ((Number) row.get("internal_id")).longValue(),
                                String.valueOf(row.get("id")),
                                String.valueOf(row.get("category")),
                                String.valueOf(row.get("name")),
                                String.valueOf(row.get("manufacturer")),
                                ((Number) row.get("price")).intValue(),
                                Map.of(),
                                1
                        ),
                        "ACTIVE",
                        Map.of("summary", "GPU benchmark", "score", 91),
                        null,
                        null
                ))
                .toList());        when(compatibilityService.partRowsWithCompatibility(eq(user), eq("QUOTE_DRAFT_CURRENT"), eq("GPU"), eq(null), eq(null), anyList()))
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
        verify(partDetailCachedLoader).detailsByPublicIds(anyList());
    }

    @Test
    void priceSortEvaluatesOnlyPageRowsWithoutRecommendationPinning() {
        // 페이지 = [싼 것(추천 rank 2), 비싼 것(추천 rank 1)] — 구 전량 경로라면 rank 1이 최상단으로
        // 끌려 올라간다. 페이지-스코프 경로는 SQL 가격순을 그대로 유지하고 뱃지만 달아야 한다.
        List<Map<String, Object>> rawRows = List.of(
                part("gpu-cheap", 103L, "GPU", "RTX 5060 Unbalanced", 500_000),
                part("gpu-balanced", 104L, "GPU", "RTX 5070 Ti Balanced", 990_000)
        );
        List<Map<String, Object>> evaluatedRows = List.of(
                withCompatibility(rawRows.get(0), "PASS", "호환 가능"),
                withCompatibility(rawRows.get(1), "PASS", "호환 가능")
        );
        CurrentUserService.CurrentUser user = user();
        org.mockito.ArgumentCaptor<String> idsSql = org.mockito.ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForList(idsSql.capture(), eq(String.class), any(Object[].class)))
                .thenReturn(rawRows.stream().map(row -> String.valueOf(row.get("id"))).toList());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(87);
        when(partDetailCachedLoader.detailsByPublicIds(anyList())).thenReturn(rawRows.stream()
                .map(row -> new PartDetailDto(
                        new ToolBuildPart(
                                ((Number) row.get("internal_id")).longValue(),
                                String.valueOf(row.get("id")),
                                String.valueOf(row.get("category")),
                                String.valueOf(row.get("name")),
                                String.valueOf(row.get("manufacturer")),
                                ((Number) row.get("price")).intValue(),
                                Map.of(),
                                1
                        ),
                        "ACTIVE",
                        Map.of("summary", "GPU benchmark", "score", 91),
                        null,
                        null
                ))
                .toList());
        when(compatibilityService.partRowsWithCompatibility(eq(user), eq("QUOTE_DRAFT_CURRENT"), eq("GPU"), eq(null), eq(null), anyList()))
                .thenReturn(evaluatedRows);

        Map<String, Object> response = service.parts(
                user, "GPU", null, null, null, null, null, 0, 10, "price_asc", "QUOTE_DRAFT_CURRENT", null, null);

        List<Map<String, Object>> items = castList(response.get("items"));
        // SQL 가격순 유지 — 추천 rank 1(비싼 것)이 위로 끌려오지 않는다.
        assertThat(items).extracting(item -> item.get("name"))
                .containsExactly("RTX 5060 Unbalanced", "RTX 5070 Ti Balanced");
        assertThat(recommendation(items.get(1)).get("rank")).isEqualTo(1);
        assertThat(recommendation(items.get(0)).get("rank")).isEqualTo(2);
        // total은 페이지 행 수가 아니라 카테고리 전체 count에서 온다.
        assertThat(response.get("total")).isEqualTo(87);
        // 후보 id 조회가 SQL 페이지네이션을 사용한다 — 전량 로드 없음.
        assertThat(idsSql.getValue()).contains("LIMIT ? OFFSET ?");
        // 평가는 페이지 행(2개)만 받는다.
        org.mockito.ArgumentCaptor<List<Map<String, Object>>> evaluatedInput = candidateRowsCaptor();
        verify(compatibilityService).partRowsWithCompatibility(eq(user), eq("QUOTE_DRAFT_CURRENT"), eq("GPU"), eq(null), eq(null), evaluatedInput.capture());
        assertThat(evaluatedInput.getValue()).hasSize(2);
    }

    @Test
    void priceSortPageResponseIsCachedPerPageAndSignature() {
        CurrentUserService.CurrentUser user = user();
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);
        when(compatibilityService.partRowsWithCompatibility(eq(user), eq("QUOTE_DRAFT_CURRENT"), eq("GPU"), eq(null), eq(null), anyList()))
                .thenReturn(List.of());

        service.parts(user, "GPU", null, null, null, null, null, 0, 10, "price_asc", "QUOTE_DRAFT_CURRENT", null, null);
        service.parts(user, "GPU", null, null, null, null, null, 0, 10, "price_asc", "QUOTE_DRAFT_CURRENT", null, null);
        service.parts(user, "GPU", null, null, null, null, null, 1, 10, "price_asc", "QUOTE_DRAFT_CURRENT", null, null);

        // 같은 (서명, 페이지)는 캐시 히트 — 평가·count는 페이지당 1회만.
        verify(compatibilityService, org.mockito.Mockito.times(2))
                .partRowsWithCompatibility(eq(user), eq("QUOTE_DRAFT_CURRENT"), eq("GPU"), eq(null), eq(null), anyList());
        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .queryForObject(anyString(), eq(Integer.class), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private static org.mockito.ArgumentCaptor<List<Map<String, Object>>> candidateRowsCaptor() {
        return org.mockito.ArgumentCaptor.forClass((Class) List.class);
    }

    @Test
    void plainPartsHydratesCandidateIdsThroughPartDetailCache() {
        Map<String, Object> rawRow = part("gpu-basic", 201L, "GPU", "RTX 5070", 890_000);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class)))
                .thenReturn(List.of("gpu-basic"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(partDetailCachedLoader.detailsByPublicIds(List.of("gpu-basic")))
                .thenReturn(List.of(new PartDetailDto(
                        new ToolBuildPart(201L, "gpu-basic", "GPU", "RTX 5070", "BuildGraph", 890_000, Map.of(), 1),
                        "ACTIVE",
                        Map.of("summary", "GPU benchmark", "score", 91),
                        null,
                        null
                )));

        Map<String, Object> response = service.parts(
                "GPU", null, null, null, null, null, 0, 20, "price_asc");

        assertThat(castList(response.get("items"))).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("id", rawRow.get("id"));
            assertThat(item).containsEntry("name", rawRow.get("name"));
            assertThat(item.get("benchmarkSummary")).isEqualTo(Map.of("summary", "GPU benchmark", "score", 91));
        });
        verify(partDetailCachedLoader).detailsByPublicIds(List.of("gpu-basic"));
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

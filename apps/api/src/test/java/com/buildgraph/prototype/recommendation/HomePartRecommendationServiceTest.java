package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class HomePartRecommendationServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RecommendationScoringClient scoringClient;

    @Mock
    private RecommendationModelRegistry modelRegistry;

    @Test
    void homePartsFallsBackAndKeepsCategoryDiversityWhenScorerFails() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                part("gpu-1", "GPU", 900000, 95),
                part("gpu-2", "GPU", 700000, 90),
                part("cpu-1", "CPU", 600000, 93),
                part("ram-1", "RAM", 180000, 80),
                part("psu-1", "PSU", 210000, 78)
        ));
        when(scoringClient.payload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("HOME_PARTS"), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of("candidates", List.of()));
        when(scoringClient.score(org.mockito.ArgumentMatchers.anyMap())).thenThrow(new IllegalStateException("scorer down"));
        HomePartRecommendationService service = new HomePartRecommendationService(jdbcTemplate, scoringClient, modelRegistry, true);

        Map<String, Object> response = service.homeParts(USER, 4);

        assertThat(response.get("fallbackUsed")).isEqualTo(true);
        List<Map<String, Object>> items = castList(response.get("items"));
        assertThat(items).hasSize(4);
        assertThat(items.stream()
                .map(item -> castMap(item.get("part")).get("category"))
                .distinct()
                .count()).isEqualTo(4);
        verifyNoInteractions(modelRegistry);
    }

    @Test
    void homePartsStopsBlockingOnScorerAfterBaselineDetected() throws Exception {
        // 첫 호출은 동기 1회 탐지(baseline 확인), 이후 호출은 스코어러로 홈 응답을 블로킹하지 않는다(감사 B9).
        // directExecutor로 비동기 기록이 인라인 실행되고, 스로틀(60s) 안의 같은 후보 집합 재호출은
        // scorer 호출·shadow 기록 모두 생략된다.
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                part("gpu-1", "GPU", 900000, 60),
                part("cpu-1", "CPU", 600000, 50)
        ));
        when(scoringClient.payload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("HOME_PARTS"), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of("candidates", List.of()));
        when(scoringClient.score(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "modelName", "baseline",
                "modelVersion", "baseline-shadow",
                "scores", List.of(
                        Map.of("partId", "gpu-1", "score", 1.0),
                        Map.of("partId", "cpu-1", "score", 2.0)
                )
        ));
        when(modelRegistry.upsertShadowModelVersion(any())).thenReturn(100L);
        HomePartRecommendationService service = new HomePartRecommendationService(
                jdbcTemplate, scoringClient, modelRegistry, true, Runnable::run, 60_000L);

        Map<String, Object> first = service.homeParts(USER, 4);   // 동기 탐지 → baseline 확정
        Map<String, Object> second = service.homeParts(USER, 4);  // 비동기 기록 경로(인라인 실행)
        Map<String, Object> third = service.homeParts(USER, 4);   // 스로틀로 scorer 호출 생략

        assertThat(first.get("fallbackUsed")).isEqualTo(true);
        assertThat(second.get("fallbackUsed")).isEqualTo(true);
        assertThat(third.get("fallbackUsed")).isEqualTo(true);
        org.mockito.Mockito.verify(scoringClient, org.mockito.Mockito.times(2))
                .score(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void homePartsUsesScorerScoreWhenScorerResponds() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                part("gpu-1", "GPU", 900000, 60),
                part("cpu-1", "CPU", 600000, 50),
                part("ram-1", "RAM", 180000, 40),
                part("psu-1", "PSU", 210000, 30)
        ));
        when(scoringClient.payload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("HOME_PARTS"), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of("candidates", List.of()));
        when(scoringClient.score(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "modelName", "xgboost-reranker",
                "modelVersion", "xgb-test",
                "scores", List.of(
                        Map.of("partId", "ram-1", "score", 99.0),
                        Map.of("partId", "gpu-1", "score", 10.0),
                        Map.of("partId", "cpu-1", "score", 9.0),
                        Map.of("partId", "psu-1", "score", 8.0)
                )
        ));
        when(modelRegistry.upsertShadowModelVersion(any())).thenReturn(100L);
        HomePartRecommendationService service = new HomePartRecommendationService(jdbcTemplate, scoringClient, modelRegistry, true);

        Map<String, Object> response = service.homeParts(USER, 4);

        assertThat(response.get("fallbackUsed")).isEqualTo(false);
        List<Map<String, Object>> items = castList(response.get("items"));
        assertThat(items).hasSize(4);
        assertThat(items.get(0).get("scoreSource")).isEqualTo("XGBOOST");
        assertThat(castMap(items.get(0).get("part")).get("id")).isEqualTo("ram-1");
        assertThat(castStringList(items.get(0).get("reasonTags"))).contains("userReaction", "freshPrice");
    }

    @Test
    void homePartsTreatsBaselineShadowScorerAsFallback() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                part("gpu-1", "GPU", 900000, 60),
                part("cpu-1", "CPU", 600000, 50),
                part("ram-1", "RAM", 180000, 40),
                part("psu-1", "PSU", 210000, 30)
        ));
        when(scoringClient.payload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("HOME_PARTS"), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Map.of("candidates", List.of()));
        when(scoringClient.score(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Map.of(
                "modelName", "baseline",
                "modelVersion", "baseline-shadow",
                "scores", List.of(
                        Map.of("partId", "ram-1", "score", 99.0),
                        Map.of("partId", "gpu-1", "score", 10.0),
                        Map.of("partId", "cpu-1", "score", 9.0),
                        Map.of("partId", "psu-1", "score", 8.0)
                )
        ));
        when(modelRegistry.upsertShadowModelVersion(any())).thenReturn(100L);
        HomePartRecommendationService service = new HomePartRecommendationService(jdbcTemplate, scoringClient, modelRegistry, true);

        Map<String, Object> response = service.homeParts(USER, 4);

        assertThat(response.get("fallbackUsed")).isEqualTo(true);
        List<Map<String, Object>> items = castList(response.get("items"));
        assertThat(items.get(0).get("scoreSource")).isEqualTo("FALLBACK");
        assertThat(items.get(0).get("modelVersion")).isNull();
        // FALLBACK이면 순위도 Java deterministicScore가 결정해야 한다. 예전에는 baseline 점수(ram-1=99)가
        // 후보 점수를 덮어쓴 채 복원되지 않아 FALLBACK 표시와 실제 순위가 어긋났다(감사 B4).
        assertThat(castMap(items.get(0).get("part")).get("id")).isEqualTo("gpu-1");
        assertThat(castStringList(items.get(0).get("reasonTags"))).doesNotContain("userReaction");
    }

    private static Map<String, Object> part(String id, String category, int price, int score) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("internal_id", Math.abs(id.hashCode()));
        row.put("id", id);
        row.put("category", category);
        row.put("name", category + " part");
        row.put("manufacturer", "BuildGraph");
        row.put("price", price);
        row.put("status", "ACTIVE");
        row.put("attributes", Map.of("toolReady", true));
        row.put("benchmark_score", score);
        row.put("external_offer_image_url", "https://example.com/" + id + ".png");
        row.put("external_offer_url", "https://example.com/" + id);
        row.put("has_fps_coverage", "GPU".equals(category) || "CPU".equals(category));
        row.put("price_age_days", 1);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        return (List<String>) value;
    }
}

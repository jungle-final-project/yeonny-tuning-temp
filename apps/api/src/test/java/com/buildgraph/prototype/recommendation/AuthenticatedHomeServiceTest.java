package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthenticatedHomeServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Mock
    private HomeCategoryPartsService homeCategoryPartsService;

    @Mock
    private HomePartRecommendationService homePartRecommendationService;

    @Test
    void authenticatedHomeUsesSharedRecommendationPayload() {
        Map<String, Object> categoryParts = Map.of("GPU", List.of(part("gpu-1", "GPU")));
        Map<String, Object> recommendedParts = Map.of(
                "items", List.of(Map.of(
                        "recommendationId", "home-part-cpu-1",
                        "rankPosition", 0,
                        "part", part("cpu-1", "CPU"),
                        "scoreSource", "FALLBACK",
                        "reasonTags", List.of("popular")
                )),
                "generatedAt", "2026-07-17T00:00:00Z",
                "fallbackUsed", true
        );
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5)).thenReturn(recommendedParts);
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService
        );

        Map<String, Object> response = service.home(USER);

        Map<String, Object> slimCategoryParts = castMap(response.get("categoryParts"));
        Map<String, Object> gpu = castList(slimCategoryParts.get("GPU")).get(0);
        assertThat(gpu)
                .containsEntry("id", "gpu-1")
                .containsEntry("name", "GPU part")
                .doesNotContainKeys("benchmarkSummary", "latestPriceSource", "latestPriceCollectedAt");
        assertThat(castMap(gpu.get("attributes"))).containsOnlyKeys("imageUrl", "shortSpec");
        assertThat(castMap(gpu.get("externalOffer"))).containsOnlyKeys("imageUrl");

        Map<String, Object> slimRecommendedParts = castMap(response.get("recommendedParts"));
        assertThat(slimRecommendedParts.get("fallbackUsed")).isEqualTo(true);
        Map<String, Object> recommendedItem = castList(slimRecommendedParts.get("items")).get(0);
        assertThat(recommendedItem.get("recommendationId")).isEqualTo("home-part-cpu-1");
        assertThat(castMap(recommendedItem.get("part")))
                .containsEntry("id", "cpu-1")
                .doesNotContainKeys("benchmarkSummary", "latestPriceSource", "latestPriceCollectedAt");
        verify(homePartRecommendationService).sharedHomeParts(5);
    }

    @Test
    void prewarmFillsSharedAuthenticatedHomeCache() {
        Map<String, Object> categoryParts = Map.of("CPU", List.of(Map.of("name", "Ryzen")));
        Map<String, Object> recommendedParts = Map.of("items", List.of(), "fallbackUsed", true);
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5)).thenReturn(recommendedParts);
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService
        );

        service.prewarm();
        Map<String, Object> response = service.home(USER);

        assertThat(castMap(response.get("categoryParts")).get("CPU")).isNotNull();
        verify(homePartRecommendationService, times(1)).sharedHomeParts(5);
    }

    @Test
    void zeroTtlDisablesSharedAuthenticatedHomeCache() {
        Map<String, Object> categoryParts = Map.of("CPU", List.of(Map.of("name", "Ryzen")));
        Map<String, Object> recommendedParts = Map.of("items", List.of(), "fallbackUsed", true);
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5)).thenReturn(recommendedParts);
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService,
                0L,
                0L,
                Runnable::run
        );

        service.home(USER);
        service.home(USER);

        verify(homePartRecommendationService, times(2)).sharedHomeParts(5);
    }

    @Test
    void prewarmRefreshesSharedAuthenticatedHomeCacheBeforeTtlExpires() {
        Map<String, Object> categoryParts = Map.of("CPU", List.of(Map.of("name", "Ryzen")));
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5))
                .thenReturn(recommendedParts("generated-1"), recommendedParts("generated-2"));
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService,
                300L,
                900L,
                Runnable::run
        );

        assertThat(castMap(service.home(USER).get("recommendedParts")).get("generatedAt")).isEqualTo("generated-1");
        service.prewarm();
        assertThat(castMap(service.home(USER).get("recommendedParts")).get("generatedAt")).isEqualTo("generated-2");

        verify(homePartRecommendationService, times(2)).sharedHomeParts(5);
    }

    private static Map<String, Object> part(String id, String category) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("category", category),
                Map.entry("name", category + " part"),
                Map.entry("manufacturer", "BuildGraph"),
                Map.entry("price", 100000),
                Map.entry("status", "ACTIVE"),
                Map.entry("attributes", Map.of(
                        "imageUrl", "https://example.com/" + id + "-attr.png",
                        "shortSpec", "home spec",
                        "toolReady", true
                )),
                Map.entry("benchmarkSummary", Map.of("summary", "fast", "score", 95)),
                Map.entry("latestPriceSource", "NAVER"),
                Map.entry("latestPriceCollectedAt", "2026-07-17T00:00:00Z"),
                Map.entry("externalOffer", Map.of(
                        "imageUrl", "https://example.com/" + id + ".png",
                        "offerUrl", "https://example.com/" + id,
                        "supplierName", "Demo Shop"
                ))
        );
    }

    private static Map<String, Object> recommendedParts(String generatedAt) {
        return Map.of(
                "items", List.of(),
                "generatedAt", generatedAt,
                "fallbackUsed", true
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }
}

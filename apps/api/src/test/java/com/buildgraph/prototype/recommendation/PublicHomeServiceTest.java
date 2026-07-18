package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublicHomeServiceTest {
    @Mock
    private HomeCategoryPartsService homeCategoryPartsService;

    @Mock
    private HomePartRecommendationService homePartRecommendationService;

    @Test
    void publicHomeReturnsStaleValueWhileRefreshingInBackground() throws Exception {
        AtomicInteger recommendationLoads = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            when(homeCategoryPartsService.priceDescCategoryParts())
                    .thenReturn(Map.of("CPU", List.of(part("cpu-1", "CPU"))));
            when(homePartRecommendationService.publicHomeParts(5)).thenAnswer(invocation -> {
                int load = recommendationLoads.incrementAndGet();
                if (load == 2) {
                    refreshStarted.countDown();
                    finishRefresh.await(5, TimeUnit.SECONDS);
                }
                return recommendedParts("generated-" + load);
            });
            PublicHomeService service = new PublicHomeService(
                    homeCategoryPartsService,
                    homePartRecommendationService,
                    Duration.ofMillis(5),
                    Duration.ofSeconds(1),
                    executor
            );

            Map<String, Object> first = service.home();
            assertThat(castMap(first.get("recommendedParts")).get("generatedAt")).isEqualTo("generated-1");
            Thread.sleep(30);

            Map<String, Object> stale = service.home();

            assertThat(castMap(stale.get("recommendedParts")).get("generatedAt")).isEqualTo("generated-1");
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(recommendationLoads.get()).isEqualTo(2);
            finishRefresh.countDown();

            assertThat(awaitGeneratedAt(service, "generated-2")).isEqualTo("generated-2");
        } finally {
            finishRefresh.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void zeroTtlDisablesPublicHomeCache() {
        when(homeCategoryPartsService.priceDescCategoryParts())
                .thenReturn(Map.of("CPU", List.of(part("cpu-1", "CPU"))));
        when(homePartRecommendationService.publicHomeParts(5)).thenReturn(recommendedParts("generated"));
        PublicHomeService service = new PublicHomeService(
                homeCategoryPartsService,
                homePartRecommendationService,
                0L,
                0L,
                Runnable::run
        );

        service.home();
        service.home();

        verify(homePartRecommendationService, times(2)).publicHomeParts(5);
    }

    @Test
    void prewarmRefreshesPublicHomeCacheBeforeTtlExpires() {
        when(homeCategoryPartsService.priceDescCategoryParts())
                .thenReturn(Map.of("CPU", List.of(part("cpu-1", "CPU"))));
        when(homePartRecommendationService.publicHomeParts(5))
                .thenReturn(recommendedParts("generated-1"), recommendedParts("generated-2"));
        PublicHomeService service = new PublicHomeService(
                homeCategoryPartsService,
                homePartRecommendationService,
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Runnable::run
        );

        assertThat(castMap(service.home().get("recommendedParts")).get("generatedAt")).isEqualTo("generated-1");
        service.prewarm();
        assertThat(castMap(service.home().get("recommendedParts")).get("generatedAt")).isEqualTo("generated-2");

        verify(homePartRecommendationService, times(2)).publicHomeParts(5);
    }

    private static String awaitGeneratedAt(PublicHomeService service, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String generatedAt = null;
        while (System.nanoTime() < deadline) {
            generatedAt = String.valueOf(castMap(service.home().get("recommendedParts")).get("generatedAt"));
            if (expected.equals(generatedAt)) {
                return generatedAt;
            }
            Thread.sleep(10);
        }
        return generatedAt;
    }

    private static Map<String, Object> part(String id, String category) {
        return Map.of(
                "id", id,
                "category", category,
                "name", category + " part",
                "price", 100000,
                "status", "ACTIVE"
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
}

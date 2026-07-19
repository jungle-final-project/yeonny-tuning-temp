package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PublicHomeService {
    private static final String CACHE_KEY = "home";

    private final HomeCategoryPartsService homeCategoryPartsService;
    private final HomePartRecommendationService homePartRecommendationService;
    private final ReadThroughTtlCache<String, Map<String, Object>> homeCache;
    private final Duration staleTtl;
    private final Executor refreshExecutor;

    @Autowired
    public PublicHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService,
            @Value("${spring.cache.type:caffeine}") String springCacheType,
            @Value("${recommendation.home-cache.ttl-seconds:30}") long cacheTtlSeconds,
            @Value("${recommendation.home-cache.stale-seconds:300}") long staleTtlSeconds
    ) {
        this(
                homeCategoryPartsService,
                homePartRecommendationService,
                cacheTtlSeconds(springCacheType, cacheTtlSeconds),
                staleTtlSeconds(springCacheType, staleTtlSeconds),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "public-home-cache-refresh");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    PublicHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService,
            long cacheTtlSeconds,
            long staleTtlSeconds,
            Executor refreshExecutor
    ) {
        this.homeCategoryPartsService = homeCategoryPartsService;
        this.homePartRecommendationService = homePartRecommendationService;
        this.homeCache = new ReadThroughTtlCache<>(Duration.ofSeconds(cacheTtlSeconds), 4);
        this.staleTtl = Duration.ofSeconds(staleTtlSeconds);
        this.refreshExecutor = refreshExecutor;
    }

    PublicHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor
    ) {
        this.homeCategoryPartsService = homeCategoryPartsService;
        this.homePartRecommendationService = homePartRecommendationService;
        this.homeCache = new ReadThroughTtlCache<>(cacheTtl, 4);
        this.staleTtl = staleTtl;
        this.refreshExecutor = refreshExecutor;
    }

    public Map<String, Object> home() {
        return homeCache.getStaleWhileRevalidate(CACHE_KEY, this::computeHome, staleTtl, refreshExecutor);
    }

    Map<String, Object> prewarm() {
        return homeCache.refresh(CACHE_KEY, this::computeHome, staleTtl);
    }

    private Map<String, Object> computeHome() {
        Map<String, Object> categoryParts = homeCategoryPartsService.priceDescCategoryParts();
        Map<String, Object> recommendedParts = homePartRecommendationService.publicHomeParts(5);
        return MockData.map(
                "categoryParts", HomePartSummaryMapper.categoryParts(categoryParts),
                "recommendedParts", HomePartSummaryMapper.recommendedParts(recommendedParts)
        );
    }

    private static long cacheTtlSeconds(String springCacheType, long cacheTtlSeconds) {
        return cacheDisabled(springCacheType) ? 0L : cacheTtlSeconds;
    }

    private static long staleTtlSeconds(String springCacheType, long staleTtlSeconds) {
        return cacheDisabled(springCacheType) ? 0L : staleTtlSeconds;
    }

    private static boolean cacheDisabled(String springCacheType) {
        return "none".equalsIgnoreCase(String.valueOf(springCacheType).trim());
    }
}

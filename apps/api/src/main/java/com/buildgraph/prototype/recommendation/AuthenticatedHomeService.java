package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedHomeService {
    public static final String CACHE_NAME = "home-authenticated";
    private static final String CACHE_KEY = "authenticated-home:v1";

    private final HomeCategoryPartsService homeCategoryPartsService;
    private final HomePartRecommendationService homePartRecommendationService;
    private final ReadThroughTtlCache<String, Map<String, Object>> homeCache;
    private final Duration staleTtl;
    private final Executor refreshExecutor;

    @Autowired
    public AuthenticatedHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService,
            @Value("${spring.cache.type:caffeine}") String springCacheType,
            @Value("${recommendation.home-cache.authenticated.ttl-seconds:300}") long cacheTtlSeconds,
            @Value("${recommendation.home-cache.authenticated.stale-seconds:900}") long staleTtlSeconds
    ) {
        this(
                homeCategoryPartsService,
                homePartRecommendationService,
                cacheTtlSeconds(springCacheType, cacheTtlSeconds),
                staleTtlSeconds(springCacheType, staleTtlSeconds),
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "home-auth-cache-refresh");
                    thread.setDaemon(true);
                    return thread;
                })
        );
    }

    AuthenticatedHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService
    ) {
        this(homeCategoryPartsService, homePartRecommendationService, 300L, 900L, Runnable::run);
    }

    AuthenticatedHomeService(
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

    public Map<String, Object> home(CurrentUserService.CurrentUser user) {
        return cachedHome();
    }

    Map<String, Object> prewarm() {
        return cachedHome();
    }

    private Map<String, Object> cachedHome() {
        return homeCache.getStaleWhileRevalidate(CACHE_KEY, this::computeHome, staleTtl, refreshExecutor);
    }

    private Map<String, Object> computeHome() {
        return MockData.map(
                "categoryParts", homeCategoryPartsService.priceDescCategoryParts(),
                "recommendedParts", homePartRecommendationService.sharedHomeParts(5)
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

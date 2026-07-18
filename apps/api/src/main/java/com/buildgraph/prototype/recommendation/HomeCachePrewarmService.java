package com.buildgraph.prototype.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "caffeine")
public class HomeCachePrewarmService {
    private static final Logger log = LoggerFactory.getLogger(HomeCachePrewarmService.class);

    private final PublicHomeService publicHomeService;
    private final HomeCategoryPartsService homeCategoryPartsService;
    private final AuthenticatedHomeService authenticatedHomeService;
    private final boolean enabled;

    public HomeCachePrewarmService(
            PublicHomeService publicHomeService,
            HomeCategoryPartsService homeCategoryPartsService,
            AuthenticatedHomeService authenticatedHomeService,
            @Value("${buildgraph.home.cache.prewarm.enabled:true}") boolean enabled
    ) {
        this.publicHomeService = publicHomeService;
        this.homeCategoryPartsService = homeCategoryPartsService;
        this.authenticatedHomeService = authenticatedHomeService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAfterReady() {
        if (!enabled) {
            log.info("Home cache prewarm skipped: disabled");
            return;
        }

        long startedAt = System.nanoTime();
        try {
            homeCategoryPartsService.priceDescCategoryParts();
            publicHomeService.home();
            authenticatedHomeService.prewarm();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info(
                    "Home cache prewarmed: categoryParts=true, publicHome=true, authenticatedHome=true, elapsedMs={}",
                    elapsedMs
            );
        } catch (Exception error) {
            log.warn("Home cache prewarm failed: {}", error.getMessage());
        }
    }
}

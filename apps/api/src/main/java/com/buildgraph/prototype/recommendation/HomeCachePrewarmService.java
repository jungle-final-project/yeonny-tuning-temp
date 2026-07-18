package com.buildgraph.prototype.recommendation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "spring.cache.type", havingValue = "caffeine")
public class HomeCachePrewarmService {
    private static final Logger log = LoggerFactory.getLogger(HomeCachePrewarmService.class);

    private final PublicHomeService publicHomeService;
    private final AuthenticatedHomeService authenticatedHomeService;
    private final boolean enabled;

    public HomeCachePrewarmService(
            PublicHomeService publicHomeService,
            AuthenticatedHomeService authenticatedHomeService,
            @Value("${buildgraph.home.cache.prewarm.enabled:true}") boolean enabled
    ) {
        this.publicHomeService = publicHomeService;
        this.authenticatedHomeService = authenticatedHomeService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAfterReady() {
        if (!enabled) {
            log.info("Home cache prewarm skipped: disabled");
            return;
        }

        prewarmAll("startup");
    }

    @Scheduled(
            fixedDelayString = "${buildgraph.home.cache.prewarm.public-refresh-delay-ms:25000}",
            initialDelayString = "${buildgraph.home.cache.prewarm.public-refresh-delay-ms:25000}"
    )
    public void prewarmPublicOnSchedule() {
        if (!enabled) {
            return;
        }
        prewarmPublic("schedule");
    }

    @Scheduled(
            fixedDelayString = "${buildgraph.home.cache.prewarm.authenticated-refresh-delay-ms:240000}",
            initialDelayString = "${buildgraph.home.cache.prewarm.authenticated-refresh-delay-ms:240000}"
    )
    public void prewarmAuthenticatedOnSchedule() {
        if (!enabled) {
            return;
        }
        prewarmAuthenticated("schedule");
    }

    private void prewarmAll(String trigger) {
        long startedAt = System.nanoTime();
        boolean publicHome = prewarmPublicCache();
        boolean authenticatedHome = prewarmAuthenticatedCache();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info(
                "Home cache prewarmed: trigger={}, publicHome={}, authenticatedHome={}, elapsedMs={}",
                trigger,
                publicHome,
                authenticatedHome,
                elapsedMs
        );
    }

    private void prewarmPublic(String trigger) {
        long startedAt = System.nanoTime();
        boolean warmed = prewarmPublicCache();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info("Public home cache prewarmed: trigger={}, success={}, elapsedMs={}", trigger, warmed, elapsedMs);
    }

    private void prewarmAuthenticated(String trigger) {
        long startedAt = System.nanoTime();
        boolean warmed = prewarmAuthenticatedCache();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        log.info("Authenticated home cache prewarmed: trigger={}, success={}, elapsedMs={}", trigger, warmed, elapsedMs);
    }

    private boolean prewarmPublicCache() {
        try {
            publicHomeService.prewarm();
            return true;
        } catch (Exception error) {
            log.warn("Public home cache prewarm failed: {}", error.getMessage());
            return false;
        }
    }

    private boolean prewarmAuthenticatedCache() {
        try {
            authenticatedHomeService.prewarm();
            return true;
        } catch (Exception error) {
            log.warn("Authenticated home cache prewarm failed: {}", error.getMessage());
            return false;
        }
    }
}

package com.buildgraph.prototype.build;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class BuildChatCachePrewarmService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatCachePrewarmService.class);
    private static final List<String> PREWARM_PROMPTS = List.of(
            "800만원으로 최고급 PC 추천해줘",
            "800만원짜리 컴퓨터 추천해줘",
            "300만원대 게임용 PC 추천해줘",
            "300만원으로 게임용 PC 추천해줘",
            "200만원 QHD 게임용 PC 추천해줘",
            "AI 학습용 800만원 이하인데 소음 낮은 PC 추천해줘",
            "맥스엘리트 파워 이슈 걱정되니 안정적인 파워 위주 PC 추천해줘",
            "고성능 GPU 추천해줘"
    );

    private final BuildChatService buildChatService;
    private final BuildChatCacheService buildChatCacheService;
    private final boolean enabled;
    private final Duration ttl;

    public BuildChatCachePrewarmService(
            BuildChatService buildChatService,
            BuildChatCacheService buildChatCacheService,
            @Value("${ai.build-chat.cache.prewarm.enabled:true}") boolean enabled,
            @Value("${ai.build-chat.cache.prewarm.ttl-seconds:3600}") long ttlSeconds
    ) {
        this.buildChatService = buildChatService;
        this.buildChatCacheService = buildChatCacheService;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmAfterReady() {
        if (!enabled) {
            log.info("Build Chat cache prewarm skipped: disabled");
            return;
        }
        CompletableFuture.runAsync(this::prewarm);
    }

    private void prewarm() {
        for (String prompt : PREWARM_PROMPTS) {
            Map<String, Object> request = Map.of("message", prompt);
            try {
                Map<String, Object> response = buildChatService.chat(request, null, null);
                buildChatCacheService.store(request, null, null, response, ttl);
                log.info("Build Chat cache prewarmed: prompt='{}', ttlSeconds={}", prompt, ttl.toSeconds());
            } catch (Exception error) {
                log.warn("Build Chat cache prewarm skipped for prompt='{}': {}", prompt, error.getMessage());
            }
        }
    }
}

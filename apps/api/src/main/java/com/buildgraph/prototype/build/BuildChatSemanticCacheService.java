package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.AiProfileConfig;
import com.buildgraph.prototype.agent.OpenAiEmbeddingClient;
import com.buildgraph.prototype.rag.RagEmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BuildChatSemanticCacheService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatSemanticCacheService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final OpenAiEmbeddingClient embeddingClient;
    private final AiProfileConfig aiProfileConfig;
    private final boolean enabled;
    private final double threshold;
    private final Duration ttl;

    @Autowired
    public BuildChatSemanticCacheService(
            JdbcTemplate jdbcTemplate,
            OpenAiEmbeddingClient embeddingClient,
            AiProfileConfig aiProfileConfig,
            @Value("${ai.build-chat.semantic-cache.enabled:true}") boolean enabled,
            @Value("${ai.build-chat.semantic-cache.threshold:0.94}") double threshold,
            @Value("${ai.build-chat.semantic-cache.ttl-seconds:600}") long ttlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.aiProfileConfig = aiProfileConfig;
        this.enabled = enabled;
        this.threshold = Math.max(0.0, Math.min(1.0, threshold));
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    private BuildChatSemanticCacheService() {
        this.jdbcTemplate = null;
        this.embeddingClient = null;
        this.aiProfileConfig = null;
        this.enabled = false;
        this.threshold = 1.0;
        this.ttl = Duration.ZERO;
    }

    public static BuildChatSemanticCacheService disabled() {
        return new BuildChatSemanticCacheService();
    }

    public Optional<Map<String, Object>> lookup(Map<String, Object> request, String requestedAiProfile, BuildChatIntentDecision decision) {
        if (!isUsable(decision)) {
            return Optional.empty();
        }
        try {
            String message = text(request == null ? null : request.get("message"));
            String vector = RagEmbeddingService.vectorLiteral(embeddingClient.embed(message));
            String profile = effectiveProfile(requestedAiProfile);
            String dataVersionHash = dataVersionHash();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    WITH input AS (
                      SELECT ?::vector AS query_embedding
                    )
                    SELECT id,
                           response_payload::text AS response_payload_text,
                           (1 - (embedding <=> input.query_embedding))::double precision AS similarity
                    FROM build_chat_semantic_cache, input
                    WHERE profile = ?
                      AND intent = ?
                      AND constraint_signature = ?
                      AND data_version_hash = ?
                      AND expires_at > now()
                    ORDER BY embedding <=> input.query_embedding ASC, created_at DESC
                    LIMIT 1
                    """, vector, profile, decision.intent().name(), decision.semanticConstraintSignature(), dataVersionHash);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> row = rows.get(0);
            double similarity = number(row.get("similarity"));
            if (similarity < threshold) {
                log.debug("Build Chat semantic cache miss: similarity={} threshold={}", similarity, threshold);
                return Optional.empty();
            }
            jdbcTemplate.update("""
                    UPDATE build_chat_semantic_cache
                    SET last_hit_at = now(),
                        hit_count = hit_count + 1
                    WHERE id = ?
                    """, row.get("id"));
            String payload = text(row.get("response_payload_text"));
            log.info("Build Chat semantic cache hit: intent={}, similarity={}", decision.intent(), similarity);
            return Optional.of(cacheableResponse(OBJECT_MAPPER.readValue(payload, MAP_TYPE)));
        } catch (Exception error) {
            log.warn("Build Chat semantic cache lookup failed; bypassing semantic cache: {}", error.getMessage());
            return Optional.empty();
        }
    }

    public void store(Map<String, Object> request, String requestedAiProfile, BuildChatIntentDecision decision, Map<String, Object> response) {
        if (!isUsable(decision) || response == null || response.isEmpty()) {
            return;
        }
        try {
            String message = text(request == null ? null : request.get("message"));
            String vector = RagEmbeddingService.vectorLiteral(embeddingClient.embed(message));
            String profile = effectiveProfile(requestedAiProfile);
            String dataVersionHash = dataVersionHash();
            // 별도 스케줄러 없이 저장 시점마다 만료 행을 기회적으로 정리해 테이블·HNSW 인덱스의 무한 누적을 막는다.
            // (데이터 버전 회전으로 도달 불가가 된 행도 TTL이 지나면 이 조건에 걸린다.)
            jdbcTemplate.update("DELETE FROM build_chat_semantic_cache WHERE expires_at < now()");
            jdbcTemplate.update("""
                    INSERT INTO build_chat_semantic_cache (
                      profile, intent, constraint_signature, message, embedding,
                      response_payload, data_version_hash, expires_at
                    )
                    VALUES (?, ?, ?, ?, ?::vector, ?::jsonb, ?, now() + (? * interval '1 second'))
                    """,
                    profile,
                    decision.intent().name(),
                    decision.semanticConstraintSignature(),
                    message,
                    vector,
                    OBJECT_MAPPER.writeValueAsString(cacheableResponse(response)),
                    dataVersionHash,
                    ttl.toSeconds());
            log.info("Build Chat semantic cache stored: intent={}, ttlSeconds={}", decision.intent(), ttl.toSeconds());
        } catch (Exception error) {
            log.warn("Build Chat semantic cache store failed; response returned without semantic caching: {}", error.getMessage());
        }
    }

    public void storeAsync(Map<String, Object> request, String requestedAiProfile, BuildChatIntentDecision decision, Map<String, Object> response) {
        if (!isUsable(decision) || response == null || response.isEmpty()) {
            return;
        }
        CompletableFuture.runAsync(() -> store(request, requestedAiProfile, decision, response))
                .exceptionally(error -> {
                    log.warn("Build Chat semantic cache async store failed; response already returned: {}", error.getMessage());
                    return null;
                });
    }

    private boolean isUsable(BuildChatIntentDecision decision) {
        return enabled
                && jdbcTemplate != null
                && embeddingClient != null
                && embeddingClient.isConfigured()
                && decision != null
                && decision.isSemanticCacheEligible();
    }

    private String effectiveProfile(String requestedAiProfile) {
        if (aiProfileConfig == null) {
            return requestedAiProfile == null || requestedAiProfile.isBlank() ? "DEFAULT" : requestedAiProfile.trim().toUpperCase();
        }
        return aiProfileConfig.buildChatProfile(requestedAiProfile).profile().name();
    }

    private String dataVersionHash() throws Exception {
        Map<String, Object> versions = jdbcTemplate.queryForMap("""
                SELECT
                  coalesce((SELECT max(coalesce(updated_at, created_at))::text FROM parts WHERE deleted_at IS NULL), 'none') AS parts_version,
                  coalesce((SELECT md5(string_agg(public_id::text || ':' || coalesce(price, 0)::text || ':' || status, ',' ORDER BY public_id)) FROM parts WHERE deleted_at IS NULL), 'none') AS parts_fingerprint,
                  coalesce((SELECT max(created_at)::text FROM benchmark_summaries WHERE deleted_at IS NULL), 'none') AS benchmark_version,
                  coalesce((SELECT max(created_at)::text FROM game_fps_benchmarks WHERE deleted_at IS NULL), 'none') AS fps_version,
                  coalesce((SELECT max(created_at)::text FROM rag_evidence WHERE agent_session_id IS NULL), 'none') AS rag_version,
                  coalesce((SELECT max(coalesce(updated_at, created_at))::text FROM part_alias_rules WHERE deleted_at IS NULL), 'none') AS alias_version
                """);
        Map<String, Object> versioned = new LinkedHashMap<>(versions);
        versioned.put("buildChatRouterVersion", "intent-router-v28");
        return sha256(OBJECT_MAPPER.writeValueAsString(versioned));
    }

    private static Map<String, Object> cacheableResponse(Map<String, Object> response) {
        Object stripped = stripVolatileTrace(response);
        if (stripped instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private static Object stripVolatileTrace(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                String textKey = String.valueOf(key);
                if ("agentSessionId".equals(textKey)) {
                    result.put(textKey, null);
                } else if ("evidenceIds".equals(textKey) || "toolInvocationIds".equals(textKey)) {
                    result.put(textKey, List.of());
                } else {
                    result.put(textKey, stripVolatileTrace(mapValue));
                }
            });
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(BuildChatSemanticCacheService::stripVolatileTrace).toList();
        }
        return value;
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }
}

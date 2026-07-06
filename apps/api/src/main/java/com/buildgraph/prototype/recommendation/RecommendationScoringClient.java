package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecommendationScoringClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final HttpClient httpClient;
    private final String endpoint;

    public RecommendationScoringClient(
            @Value("${recommendation.reranker.endpoint:http://localhost:8091/score}") String endpoint,
            @Value("${recommendation.reranker.timeout-ms:1200}") long timeoutMs
    ) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100, timeoutMs)))
                .build();
    }

    public Map<String, Object> score(Map<String, Object> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("scorer returned HTTP " + response.statusCode());
        }
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    /**
     * 스코어러의 현재 상태를 읽는다(읽기 전용). 응답의 featureSchema.features는 지금 서빙 중인
     * 피처 계약이며, 활성화 게이트(M6)가 모델의 feature_schema와 대조하는 데 쓴다. reload와 달리
     * 모델을 로드하지 않으므로 스코어러 인메모리 상태를 바꾸지 않는다.
     */
    public Map<String, Object> health() {
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri())
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("scorer health returned HTTP " + response.statusCode());
            }
            return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("scorer health failed: " + error.getMessage(), error);
        }
    }

    public Map<String, Object> reload(String modelPath) {
        try {
            HttpRequest request = HttpRequest.newBuilder(reloadUri())
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            OBJECT_MAPPER.writeValueAsString(MockData.map("modelPath", modelPath)),
                            StandardCharsets.UTF_8
                    ))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("scorer reload returned HTTP " + response.statusCode());
            }
            return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("scorer reload failed: " + error.getMessage(), error);
        }
    }

    public Map<String, Object> payload(
            String requestHash,
            String profile,
            boolean activeRerankEnabled,
            List<Map<String, Object>> candidates
    ) {
        return MockData.map(
                "requestHash", requestHash,
                "profile", profile,
                "activeRerankEnabled", activeRerankEnabled,
                "candidates", candidates
        );
    }

    private URI reloadUri() {
        return siblingUri("/reload");
    }

    private URI healthUri() {
        return siblingUri("/health");
    }

    private URI siblingUri(String siblingPath) {
        URI scoreUri = URI.create(endpoint);
        String path = scoreUri.getPath();
        String resolved = path == null || path.isBlank() || "/".equals(path)
                ? siblingPath
                : path.replaceFirst("/score$", siblingPath);
        if (resolved.equals(path)) {
            resolved = siblingPath;
        }
        return URI.create(scoreUri.getScheme() + "://" + scoreUri.getAuthority() + resolved);
    }
}

package com.buildgraph.prototype.opsagent.profile;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class OpenAiEmbeddingClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public OpenAiEmbeddingClient(
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.embedding-model:text-embedding-3-small}") String model,
            @Value("${openai.embedding-dimensions:1536}") int dimensions
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .build();
        this.apiKey = blankToNull(apiKey);
        this.model = blankToNull(model) == null ? "text-embedding-3-small" : model.trim();
        this.dimensions = dimensions <= 0 ? 1536 : dimensions;
    }

    public boolean isConfigured() {
        return apiKey != null;
    }

    public String model() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    public List<Double> embed(String input) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }
        String text = blankToNull(input);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "embedding input이 필요합니다.");
        }
        Map<String, Object> request = MockData.map(
                "model", model,
                "input", text,
                "dimensions", dimensions
        );
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(MAP_RESPONSE);
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI embedding 호출 실패: HTTP " + error.getStatusCode().value(),
                    error
            );
        }
        List<Double> embedding = extractEmbedding(response);
        if (embedding.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI embedding 응답을 찾을 수 없습니다.");
        }
        return embedding;
    }

    @SuppressWarnings("unchecked")
    private static List<Double> extractEmbedding(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> dataItems) || dataItems.isEmpty()) {
            return List.of();
        }
        Object first = dataItems.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            return List.of();
        }
        Object embedding = firstMap.get("embedding");
        if (!(embedding instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .toList();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

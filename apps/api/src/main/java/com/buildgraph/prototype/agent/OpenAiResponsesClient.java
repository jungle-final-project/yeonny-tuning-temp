package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import java.util.LinkedHashMap;
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
public class OpenAiResponsesClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    public OpenAiResponsesClient(
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-5.5}") String model,
            @Value("${openai.reasoning-effort:medium}") String reasoningEffort
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .build();
        this.apiKey = blankToNull(apiKey);
        this.model = blankToNull(model) == null ? "gpt-5.5" : model.trim();
        this.reasoningEffort = blankToNull(reasoningEffort) == null ? "medium" : reasoningEffort.trim();
    }

    public boolean isConfigured() {
        return apiKey != null;
    }

    public String model() {
        return model;
    }

    public String createSummary(String systemPrompt, String userPrompt) {
        return createResponse(systemPrompt, userPrompt, Map.of()).text();
    }

    public String createStructuredJson(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema
    ) {
        return createStructuredJsonResult(systemPrompt, userPrompt, schemaName, jsonSchema, model, reasoningEffort).text();
    }

    public LlmResponseResult createStructuredJsonResult(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema,
            String requestedModel,
            String requestedReasoningEffort
    ) {
        return createStructuredJsonResult(systemPrompt, userPrompt, schemaName, jsonSchema, requestedModel, requestedReasoningEffort, null);
    }

    public LlmResponseResult createStructuredJsonResult(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema,
            String requestedModel,
            String requestedReasoningEffort,
            Integer requestedMaxOutputTokens
    ) {
        Map<String, Object> structuredOutput = MockData.map(
                "text", MockData.map(
                        "format", MockData.map(
                                "type", "json_schema",
                                "name", schemaName,
                                "schema", jsonSchema,
                                "strict", true
                        )
                )
        );
        return createResponse(systemPrompt, userPrompt, structuredOutput, requestedModel, requestedReasoningEffort, requestedMaxOutputTokens);
    }

    private LlmResponseResult createResponse(String systemPrompt, String userPrompt, Map<String, Object> extraRequestFields) {
        return createResponse(systemPrompt, userPrompt, extraRequestFields, model, reasoningEffort, null);
    }

    private LlmResponseResult createResponse(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> extraRequestFields,
            String requestedModel,
            String requestedReasoningEffort,
            Integer requestedMaxOutputTokens
    ) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }
        String effectiveModel = blankToNull(requestedModel) == null ? model : requestedModel.trim();
        String effectiveReasoningEffort = blankToNull(requestedReasoningEffort) == null ? reasoningEffort : requestedReasoningEffort.trim();
        Map<String, Object> request = requestBody(systemPrompt, userPrompt, extraRequestFields, effectiveModel, effectiveReasoningEffort, requestedMaxOutputTokens);
        long startedAt = System.nanoTime();
        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(MAP_RESPONSE);
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI 호출 실패: HTTP " + error.getStatusCode().value(),
                    error
            );
        }
        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        String output = extractOutputText(response);
        if (output == null || output.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI 응답에서 summary text를 찾을 수 없습니다.");
        }
        return new LlmResponseResult(
                output.trim(),
                LlmProvider.OPENAI,
                effectiveModel,
                effectiveReasoningEffort,
                latencyMs,
                usageValue(response, "input_tokens"),
                usageValue(response, "output_tokens"),
                usageValue(response, "total_tokens")
        );
    }

    Map<String, Object> requestBody(String systemPrompt, String userPrompt, Map<String, Object> extraRequestFields) {
        return requestBody(systemPrompt, userPrompt, extraRequestFields, model, reasoningEffort);
    }

    Map<String, Object> requestBody(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> extraRequestFields,
            String requestedModel,
            String requestedReasoningEffort
    ) {
        return requestBody(systemPrompt, userPrompt, extraRequestFields, requestedModel, requestedReasoningEffort, null);
    }

    Map<String, Object> requestBody(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> extraRequestFields,
            String requestedModel,
            String requestedReasoningEffort,
            Integer requestedMaxOutputTokens
    ) {
        String effectiveModel = blankToNull(requestedModel) == null ? model : requestedModel.trim();
        String effectiveReasoningEffort = blankToNull(requestedReasoningEffort) == null ? reasoningEffort : requestedReasoningEffort.trim();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", effectiveModel);
        if (supportsReasoningEffort(effectiveModel)) {
            request.put("reasoning", Map.of("effort", effectiveReasoningEffort));
        }
        request.put("instructions", systemPrompt);
        request.put("input", userPrompt);
        if (requestedMaxOutputTokens != null && requestedMaxOutputTokens > 0) {
            request.put("max_output_tokens", requestedMaxOutputTokens);
        }
        request.putAll(extraRequestFields);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Integer usageValue(Map<String, Object> response, String key) {
        Object usage = response == null ? null : response.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return null;
        }
        Object value = usageMap.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private static boolean supportsReasoningEffort(String model) {
        String normalized = model == null ? "" : model.toLowerCase();
        return normalized.startsWith("gpt-5") || normalized.startsWith("o");
    }

    @SuppressWarnings("unchecked")
    private static String extractOutputText(Map<String, Object> response) {
        Object directOutput = response == null ? null : response.get("output_text");
        if (directOutput instanceof String text && !text.isBlank()) {
            return text;
        }
        Object output = response == null ? null : response.get("output");
        if (!(output instanceof List<?> outputItems)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (Object outputItem : outputItems) {
            if (!(outputItem instanceof Map<?, ?> item)) {
                continue;
            }
            Object content = item.get("content");
            if (!(content instanceof List<?> contentItems)) {
                continue;
            }
            for (Object contentItem : contentItems) {
                if (!(contentItem instanceof Map<?, ?> contentMap)) {
                    continue;
                }
                Object type = contentMap.get("type");
                Object text = contentMap.get("text");
                if ("output_text".equals(type) && text instanceof String textValue) {
                    builder.append(textValue);
                }
            }
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}

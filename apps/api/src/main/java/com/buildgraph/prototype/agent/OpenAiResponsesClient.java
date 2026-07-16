package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
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
    private final LlmCallBulkhead bulkhead;

    @Autowired
    public OpenAiResponsesClient(
            LlmCallBulkhead bulkhead,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-5.5}") String model,
            @Value("${openai.reasoning-effort:medium}") String reasoningEffort,
            @Value("${openai.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${openai.read-timeout-ms:20000}") long readTimeoutMs
    ) {
        this.bulkhead = bulkhead == null ? LlmCallBulkhead.directForTests() : bulkhead;
        // 명시적 connect/read 타임아웃 — OpenAI가 느리거나 먹통이어도 스레드가 무한정 묶이지 않게 한다.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(Math.max(500L, connectTimeoutMs)))
                .withReadTimeout(Duration.ofMillis(Math.max(1000L, readTimeoutMs)));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        this.restClient = RestClient.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .build();
        this.apiKey = blankToNull(apiKey);
        this.model = blankToNull(model) == null ? "gpt-5.5" : model.trim();
        this.reasoningEffort = blankToNull(reasoningEffort) == null ? "medium" : reasoningEffort.trim();
    }

    /** 요청 바디 조립 등 HTTP를 타지 않는 단위 테스트용 — 격벽/타임아웃 기본값으로 구성한다. */
    OpenAiResponsesClient(String baseUrl, String apiKey, String model, String reasoningEffort) {
        this(LlmCallBulkhead.directForTests(), baseUrl, apiKey, model, reasoningEffort, 3000L, 20000L);
    }

    public boolean isConfigured() {
        return apiKey != null;
    }

    public String model() {
        return model;
    }

    public String createSummary(String systemPrompt, String userPrompt) {
        return createSummaryResult(systemPrompt, userPrompt).text();
    }

    public LlmResponseResult createSummaryResult(String systemPrompt, String userPrompt) {
        return createResponse(systemPrompt, userPrompt, Map.of());
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
            // 실제 HTTP는 격벽 전용 스레드풀에서 실행한다 — 요청(Tomcat) 스레드 고갈을 막고,
            // 동시 상한 초과 시 대기 없이 거절해 호출부가 결정론 폴백으로 강등하게 한다.
            response = bulkhead.call(() -> restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(MAP_RESPONSE));
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI 호출 실패: HTTP " + error.getStatusCode().value(),
                    error
            );
        } catch (LlmBulkheadRejectedException | LlmBulkheadTimeoutException overload) {
            // 격벽 포화·타임아웃은 일시적 과부하다 — 503으로 내려 호출부(BuildChatService)가 결정론 폴백/우아한 거절로 강등한다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "LLM 일시적 과부하", overload);
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
                usageValue(response, "total_tokens"),
                reasoningTokens(response)
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

    // Responses API usage.output_tokens_details.reasoning_tokens — reasoning burst 진단용.
    private static Integer reasoningTokens(Map<String, Object> response) {
        Object usage = response == null ? null : response.get("usage");
        if (!(usage instanceof Map<?, ?> usageMap)) {
            return null;
        }
        Object details = usageMap.get("output_tokens_details");
        if (!(details instanceof Map<?, ?> detailsMap)) {
            return null;
        }
        Object value = detailsMap.get("reasoning_tokens");
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

package com.buildgraph.prototype.quoteagent.llm;

import com.buildgraph.prototype.opsagent.profile.*;


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
/* LLM 직접 접속부 */
public class AiChatClient {
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    /* implement 하는 과정 */
    public AiChatClient(
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
        return createSummaryResult(systemPrompt, userPrompt).text();
    }

    public LLMresponseDto createSummaryResult(String systemPrompt, String userPrompt) {
        return null;
    }

    public String createStructuredJson(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema
    ) {
        return createStructuredJsonResult(systemPrompt, userPrompt, schemaName, jsonSchema, model, reasoningEffort).text();
    }

    public LLMresponseDto createStructuredJsonResult(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema,
            String requestedModel,
            String requestedReasoningEffort
    ) {
        return generateLLMresponse(systemPrompt, userPrompt, schemaName, jsonSchema, requestedModel, requestedReasoningEffort, null);
    }

    /* AiChat에서 호출하는 LLM 접합부 */
    @SuppressWarnings("null")
    public LLMresponseDto generateLLMresponse(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema,
            String requestedModel,
            String requestedReasoningEffort,
            Integer requestedMaxOutputTokens
    ) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }

        /* 요청 및 응답 객체 만들기 */
        String effectiveModel = blankToNull(requestedModel) == null ? model : requestedModel.trim();
        String effectiveReasoningEffort = blankToNull(requestedReasoningEffort) == null ? reasoningEffort : requestedReasoningEffort.trim();
        Map<String, Object> request = aiChatRequestBody(
                                        systemPrompt,
                                        userPrompt,
                                        schemaName,
                                        jsonSchema,
                                        effectiveModel,
                                        requestedMaxOutputTokens
                                    );
        
        /* LLM 모델에 실제 접근해서 사용 + 시작 */
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
                    "OpenAI 호출 실패: HTTP " + error.getStatusCode().value() + safeErrorBody(error),
                    error
            );
        }

        /* 응답시간 기록 */
        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        String output = extractOutputText(response);
        
        return new LLMresponseDto(
            output.trim(),
            LlmProvider.OPENAI,
            effectiveModel,
            effectiveReasoningEffort,
            latencyMs
        );
    }

    /* AiChat용 Java Map 조립 함수 */
    private Map<String, Object> aiChatRequestBody(
            String systemPrompt,
            String userPrompt,
            String schemaName,
            Map<String, Object> jsonSchema,
            String effectiveModel,
            Integer requestedMaxOutputTokens
    ) {
        Map<String, Object> request = new LinkedHashMap<>();

        request.put("model", effectiveModel);
        request.put("instructions", systemPrompt);
        request.put("input", userPrompt);
        request.put("text", MockData.map(
                "format", MockData.map(
                        "type", "json_schema",
                        "name", schemaName,
                        "schema", jsonSchema,
                        "strict", true
                )
        ));
        if (requestedMaxOutputTokens != null && requestedMaxOutputTokens > 0) {
            request.put("max_output_tokens", requestedMaxOutputTokens);
        }

        return request;
    }

    /* 특정 부분 텍스트 추출하여 전송 */
    @SuppressWarnings("unchecked")
    private static String extractOutputText(Map<String, Object> response) {
        if (response == null || response.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI 응답이 비어 있습니다.");
        }

        Object outputText = response.get("output_text");
        if (outputText instanceof String text) {
            return text;
        }

        List<Map<String, Object>> output = (List<Map<String, Object>>) response.get("output");
        if (output == null || output.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI 응답 output을 찾을 수 없습니다.");
        }
        Map<String, Object> message = output.get(0);

        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        if (content == null || content.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI 응답 content를 찾을 수 없습니다.");
        }
        Map<String, Object> textBlock = content.get(0);

        Object text = textBlock.get("text");
        if (text instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI 응답 text를 찾을 수 없습니다.");
    }

    private static String safeErrorBody(RestClientResponseException error) {
        String body = error.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        String truncated = compact.length() > 500 ? compact.substring(0, 500) + "..." : compact;
        return ": " + truncated;
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

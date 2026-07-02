package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LlmGenerationService {
    private final JdbcTemplate jdbcTemplate;

    public LlmGenerationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String recordSuccess(
            Long agentSessionId,
            AiProfileDefinition profile,
            String schemaName,
            LlmResponseResult result
    ) {
        return recordSuccess(agentSessionId, profile, schemaName, result, Map.of());
    }

    public String recordSuccess(
            Long agentSessionId,
            AiProfileDefinition profile,
            String schemaName,
            LlmResponseResult result,
            Map<String, Object> stageTimings
    ) {
        return recordSuccess(agentSessionId, profile, "AS_CHAT", schemaName, result, stageTimings);
    }

    public String recordSuccess(
            Long agentSessionId,
            AiProfileDefinition profile,
            String useCase,
            String schemaName,
            LlmResponseResult result,
            Map<String, Object> stageTimings
    ) {
        return record(
                agentSessionId,
                profile,
                useCase,
                "SUCCESS",
                schemaName,
                true,
                result.latencyMs(),
                result.inputTokens(),
                result.outputTokens(),
                result.totalTokens(),
                null,
                null,
                stageTimings
        );
    }

    public String recordFailure(
            Long agentSessionId,
            AiProfileDefinition profile,
            String schemaName,
            boolean schemaValid,
            Long latencyMs,
            String errorCode,
            String errorMessage
    ) {
        return recordFailure(agentSessionId, profile, schemaName, schemaValid, latencyMs, errorCode, errorMessage, Map.of());
    }

    public String recordFailure(
            Long agentSessionId,
            AiProfileDefinition profile,
            String schemaName,
            boolean schemaValid,
            Long latencyMs,
            String errorCode,
            String errorMessage,
            Map<String, Object> stageTimings
    ) {
        return recordFailure(agentSessionId, profile, "AS_CHAT", schemaName, schemaValid, latencyMs, errorCode, errorMessage, stageTimings);
    }

    public String recordFailure(
            Long agentSessionId,
            AiProfileDefinition profile,
            String useCase,
            String schemaName,
            boolean schemaValid,
            Long latencyMs,
            String errorCode,
            String errorMessage,
            Map<String, Object> stageTimings
    ) {
        return record(
                agentSessionId,
                profile,
                useCase,
                "FAILED",
                schemaName,
                schemaValid,
                latencyMs,
                null,
                null,
                null,
                errorCode,
                truncate(errorMessage, 500),
                stageTimings
        );
    }

    public void updateStageTimings(String generationId, Map<String, Object> stageTimings) {
        if (generationId == null || generationId.isBlank() || stageTimings == null || stageTimings.isEmpty()) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE llm_generations
                SET request_metadata = coalesce(request_metadata, '{}'::jsonb)
                    || jsonb_build_object('stageTimings', ?::jsonb)
                WHERE public_id = ?::uuid
                """, AgentTraceService.json(stageTimings), generationId);
    }

    public List<Map<String, Object>> generationsBySession(String sessionId) {
        return jdbcTemplate.queryForList("""
                        SELECT g.public_id::text AS id,
                               g.ai_profile,
                               g.provider,
                               g.model,
                               g.reasoning_effort,
                               g.use_case,
                               g.status,
                               g.schema_name,
                               g.latency_ms,
                               g.input_tokens,
                               g.output_tokens,
                               g.total_tokens,
                               g.schema_valid,
                               g.error_code,
                               g.request_metadata,
                               g.created_at
                        FROM llm_generations g
                        JOIN agent_sessions s ON s.id = g.agent_session_id
                        WHERE s.public_id = ?::uuid
                        ORDER BY g.created_at, g.id
                        """, sessionId)
                .stream()
                .map(this::generationMap)
                .toList();
    }

    private String record(
            Long agentSessionId,
            AiProfileDefinition profile,
            String useCase,
            String status,
            String schemaName,
            boolean schemaValid,
            Long latencyMs,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String errorCode,
            String errorMessage,
            Map<String, Object> stageTimings
    ) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO llm_generations (
                  agent_session_id,
                  ai_profile,
                  provider,
                  model,
                  reasoning_effort,
                  use_case,
                  status,
                  schema_name,
                  rag_top_k,
                  prompt_version,
                  latency_ms,
                  input_tokens,
                  output_tokens,
                  total_tokens,
                  schema_valid,
                  error_code,
                  error_message,
                  request_metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING public_id::text AS id
                """,
                agentSessionId,
                profile.profile().name(),
                profile.provider().storageValue(),
                profile.model(),
                profile.reasoningEffort(),
                useCase,
                status,
                schemaName,
                profile.ragTopK(),
                profile.promptVersion(),
                latencyMs,
                inputTokens,
                outputTokens,
                totalTokens,
                schemaValid,
                errorCode,
                errorMessage,
                AgentTraceService.json(requestMetadata(profile, stageTimings)));
        return DbValueMapper.string(row, "id");
    }

    private static Map<String, Object> requestMetadata(AiProfileDefinition profile, Map<String, Object> stageTimings) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(MockData.map(
                        "ragTopK", profile.ragTopK(),
                        "promptVersion", profile.promptVersion(),
                        "maxOutputTokens", profile.maxOutputTokens(),
                        "recentMessageLimit", profile.recentMessageLimit(),
                        "includeEvidenceChunkText", profile.includeEvidenceChunkText(),
                        "includeToolResultPayload", profile.includeToolResultPayload(),
                        "useCompactPrompt", profile.useCompactPrompt()
                ));
        if (stageTimings != null && !stageTimings.isEmpty()) {
            metadata.put("stageTimings", stageTimings);
        }
        return metadata;
    }

    private Map<String, Object> generationMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "aiProfile", DbValueMapper.string(row, "ai_profile"),
                "provider", DbValueMapper.string(row, "provider"),
                "model", DbValueMapper.string(row, "model"),
                "reasoningEffort", DbValueMapper.string(row, "reasoning_effort"),
                "useCase", DbValueMapper.string(row, "use_case"),
                "status", DbValueMapper.string(row, "status"),
                "schemaName", DbValueMapper.string(row, "schema_name"),
                "latencyMs", DbValueMapper.integer(row, "latency_ms"),
                "inputTokens", DbValueMapper.integer(row, "input_tokens"),
                "outputTokens", DbValueMapper.integer(row, "output_tokens"),
                "totalTokens", DbValueMapper.integer(row, "total_tokens"),
                "schemaValid", row.get("schema_valid"),
                "errorCode", DbValueMapper.string(row, "error_code"),
                "requestMetadata", DbValueMapper.json(row, "request_metadata", Map.of()),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

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
        return record(
                agentSessionId,
                profile,
                "SUCCESS",
                schemaName,
                true,
                result.latencyMs(),
                result.inputTokens(),
                result.outputTokens(),
                result.totalTokens(),
                null,
                null
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
        return record(
                agentSessionId,
                profile,
                "FAILED",
                schemaName,
                schemaValid,
                latencyMs,
                null,
                null,
                null,
                errorCode,
                truncate(errorMessage, 500)
        );
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
            String status,
            String schemaName,
            boolean schemaValid,
            Long latencyMs,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            String errorCode,
            String errorMessage
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
                VALUES (?, ?, ?, ?, ?, 'AS_CHAT', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING public_id::text AS id
                """,
                agentSessionId,
                profile.profile().name(),
                profile.provider().storageValue(),
                profile.model(),
                profile.reasoningEffort(),
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
                AgentTraceService.json(MockData.map(
                        "ragTopK", profile.ragTopK(),
                        "promptVersion", profile.promptVersion(),
                        "maxOutputTokens", profile.maxOutputTokens(),
                        "recentMessageLimit", profile.recentMessageLimit(),
                        "includeEvidenceChunkText", profile.includeEvidenceChunkText(),
                        "includeToolResultPayload", profile.includeToolResultPayload(),
                        "useCompactPrompt", profile.useCompactPrompt()
                )));
        return DbValueMapper.string(row, "id");
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

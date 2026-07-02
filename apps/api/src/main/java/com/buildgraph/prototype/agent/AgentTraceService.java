package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentTraceService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED_TOOL_NAMES = Set.of(
            "compatibility",
            "power",
            "size",
            "performance",
            "price"
    );
    private final JdbcTemplate jdbcTemplate;

    public AgentTraceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String createQueuedSession(AgentSessionRoot root, String actor) {
        return createQueuedSession(root, actor, root.purpose());
    }

    public String createQueuedSession(AgentSessionRoot root, String actor, AgentPurpose purpose) {
        return createQueuedSession(root, actor, purpose, null);
    }

    public String createQueuedSession(AgentSessionRoot root, String actor, AgentPurpose purpose, Long userId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO agent_sessions (
                  user_id,
                  requirement_id,
                  build_id,
                  as_ticket_id,
                  status,
                  state_timeline
                )
                VALUES (
                  COALESCE(
                    ?,
                    (SELECT user_id FROM requirements WHERE public_id = ?::uuid),
                    (SELECT r.user_id FROM builds b JOIN requirements r ON r.id = b.requirement_id WHERE b.public_id = ?::uuid),
                    (SELECT user_id FROM as_tickets WHERE public_id = ?::uuid)
                  ),
                  (SELECT id FROM requirements WHERE public_id = ?::uuid),
                  (SELECT id FROM builds WHERE public_id = ?::uuid),
                  (SELECT id FROM as_tickets WHERE public_id = ?::uuid),
                  'QUEUED',
                  ?::jsonb
                )
                RETURNING public_id::text AS id
                """,
                userId,
                root.requirementId(),
                root.buildId(),
                root.asTicketId(),
                root.requirementId(),
                root.buildId(),
                root.asTicketId(),
                json(List.of(timelineItem(null, "QUEUED", actor, "session created for " + purpose))));
        return DbValueMapper.string(row, "id");
    }

    public String recordRagEvidence(String sessionId, AgentRagEvidenceDraft draft) {
        validateSessionId(sessionId);
        validateRagEvidenceDraft(draft);
        Map<String, Object> metadata = draft.metadata() == null ? Map.of() : draft.metadata();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                INSERT INTO rag_evidence (
                  agent_session_id,
                  source_id,
                  chunk_text,
                  summary,
                  score,
                  metadata
                )
                SELECT
                  s.id,
                  ?,
                  ?,
                  ?,
                  ?::numeric,
                  ?::jsonb
                FROM agent_sessions s
                WHERE s.public_id = ?::uuid
                RETURNING public_id::text AS id
                """,
                draft.sourceId().trim(),
                draft.chunkText().trim(),
                draft.summary().trim(),
                draft.score(),
                json(metadata),
                sessionId);
        return rows.stream()
                .findFirst()
                .map(row -> DbValueMapper.string(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다."));
    }

    public String recordToolInvocation(String sessionId, AgentToolInvocationDraft draft) {
        validateSessionId(sessionId);
        validateToolInvocationDraft(draft);
        Map<String, Object> requestPayload = draft.requestPayload() == null ? Map.of() : draft.requestPayload();
        Map<String, Object> resultPayload = draft.resultPayload() == null ? Map.of() : draft.resultPayload();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                INSERT INTO tool_invocations (
                  agent_session_id,
                  tool_name,
                  status,
                  confidence,
                  summary,
                  request_payload,
                  result_payload,
                  latency_ms
                )
                SELECT
                  s.id,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?
                FROM agent_sessions s
                WHERE s.public_id = ?::uuid
                RETURNING public_id::text AS id
                """,
                normalizeToolName(draft.toolName()),
                draft.status().name(),
                draft.confidence().name(),
                draft.summary().trim(),
                json(requestPayload),
                json(resultPayload),
                draft.latencyMs(),
                sessionId);
        return rows.stream()
                .findFirst()
                .map(row -> DbValueMapper.string(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다."));
    }

    public void advanceStatus(String sessionId, AgentStatus to, String actor, String reason) {
        validateSessionId(sessionId);
        if (to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "변경할 Agent status가 필요합니다.");
        }
        requireText(actor, "Agent status 변경 actor가 필요합니다.");
        Map<String, Object> row = sessionTraceRow(sessionId);
        AgentStatus from = parseStatus(DbValueMapper.string(row, "status"));
        if (!canTransition(from, to)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "허용되지 않는 Agent status 전이입니다: " + from + " -> " + to
            );
        }
        List<Object> timeline = appendTimeline(row, from.name(), to.name(), actor.trim(), reason);
        int updated = jdbcTemplate.update("""
                UPDATE agent_sessions
                SET status = ?,
                    state_timeline = ?::jsonb,
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND status = ?
                """, to.name(), json(timeline), sessionId, from.name());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent session 상태가 변경되어 다시 시도해야 합니다.");
        }
    }

    public void updateSummary(String sessionId, String summary) {
        validateSessionId(sessionId);
        requireText(summary, "Agent summary가 필요합니다.");
        int updated = jdbcTemplate.update("""
                UPDATE agent_sessions
                SET summary = ?,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, summary.trim(), sessionId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다.");
        }
    }

    public void markFailed(String sessionId, String actor, String reason) {
        validateSessionId(sessionId);
        requireText(actor, "Agent status 변경 actor가 필요합니다.");
        Map<String, Object> row = sessionTraceRow(sessionId);
        AgentStatus from = parseStatus(DbValueMapper.string(row, "status"));
        if (from == AgentStatus.FAILED || from == AgentStatus.SUCCEEDED || from == AgentStatus.CANCELLED) {
            return;
        }
        if (from != AgentStatus.FALLBACK_READY) {
            updateSummary(sessionId, "Agent worker failed: " + (reason == null || reason.isBlank() ? "unknown error" : reason));
        }
        advanceStatus(sessionId, AgentStatus.FAILED, actor.trim(), reason);
    }

    static Map<String, Object> timelineItem(String from, String to, String actor, String reason) {
        return MockData.map("from", from, "to", to, "at", MockData.now(), "actor", actor, "reason", reason);
    }

    static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 변환에 실패했습니다.", e);
        }
    }

    private Map<String, Object> sessionTraceRow(String sessionId) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               status,
                               state_timeline
                        FROM agent_sessions
                        WHERE public_id = ?::uuid
                        """, sessionId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다."));
    }

    private static List<Object> appendTimeline(Map<String, Object> row, String from, String to, String actor, String reason) {
        List<Object> timeline = new ArrayList<>();
        Object currentTimeline = DbValueMapper.json(row, "state_timeline", List.of());
        if (currentTimeline instanceof List<?> currentItems) {
            timeline.addAll(currentItems);
        }
        timeline.add(timelineItem(from, to, actor, reason));
        return timeline;
    }

    private static AgentStatus parseStatus(String value) {
        try {
            return AgentStatus.valueOf(value);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent session status를 해석할 수 없습니다.", e);
        }
    }

    private static boolean canTransition(AgentStatus from, AgentStatus to) {
        return switch (from) {
            case QUEUED -> to == AgentStatus.RUNNING || to == AgentStatus.FAILED || to == AgentStatus.CANCELLED;
            case RUNNING -> to == AgentStatus.RAG_SEARCHED || to == AgentStatus.FALLBACK_READY
                    || to == AgentStatus.FAILED || to == AgentStatus.CANCELLED;
            case RAG_SEARCHED -> to == AgentStatus.TOOLS_CALLED || to == AgentStatus.FALLBACK_READY || to == AgentStatus.FAILED;
            case TOOLS_CALLED -> to == AgentStatus.SUMMARY_READY || to == AgentStatus.FALLBACK_READY || to == AgentStatus.FAILED;
            case SUMMARY_READY -> to == AgentStatus.SUCCEEDED || to == AgentStatus.FALLBACK_READY || to == AgentStatus.FAILED;
            case FALLBACK_READY -> to == AgentStatus.SUCCEEDED || to == AgentStatus.FAILED;
            case SUCCEEDED, FAILED, CANCELLED -> false;
        };
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent session id가 필요합니다.");
        }
    }

    private static void validateRagEvidenceDraft(AgentRagEvidenceDraft draft) {
        if (draft == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAG 근거 초안이 필요합니다.");
        }
        requireText(draft.sourceId(), "RAG sourceId가 필요합니다.");
        requireText(draft.chunkText(), "RAG chunkText가 필요합니다.");
        requireText(draft.summary(), "RAG summary가 필요합니다.");
        BigDecimal score = draft.score();
        if (score != null && (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RAG score는 0 이상 1 이하이어야 합니다.");
        }
    }

    private static void validateToolInvocationDraft(AgentToolInvocationDraft draft) {
        if (draft == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tool 호출 초안이 필요합니다.");
        }
        String toolName = normalizeToolName(draft.toolName());
        if (!SUPPORTED_TOOL_NAMES.contains(toolName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 Tool 이름입니다.");
        }
        if (draft.status() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tool status가 필요합니다.");
        }
        if (draft.confidence() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tool confidence가 필요합니다.");
        }
        requireText(draft.summary(), "Tool summary가 필요합니다.");
        Integer latencyMs = draft.latencyMs();
        if (latencyMs != null && latencyMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tool latencyMs는 0 이상이어야 합니다.");
        }
    }

    private static String normalizeToolName(String toolName) {
        requireText(toolName, "Tool 이름이 필요합니다.");
        return toolName.trim().toLowerCase();
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}

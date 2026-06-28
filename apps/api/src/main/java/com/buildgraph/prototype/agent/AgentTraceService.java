package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentTraceService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;

    public AgentTraceService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String createQueuedSession(AgentSessionRoot root, String actor) {
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
                  (SELECT id FROM users WHERE email = 'user@example.com'),
                  (SELECT id FROM requirements WHERE public_id = ?::uuid),
                  (SELECT id FROM builds WHERE public_id = ?::uuid),
                  (SELECT id FROM as_tickets WHERE public_id = ?::uuid),
                  'QUEUED',
                  ?::jsonb
                )
                RETURNING public_id::text AS id
                """,
                root.requirementId(),
                root.buildId(),
                root.asTicketId(),
                json(List.of(timelineItem(null, "QUEUED", actor, "session created for " + root.purpose()))));
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

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }
}
